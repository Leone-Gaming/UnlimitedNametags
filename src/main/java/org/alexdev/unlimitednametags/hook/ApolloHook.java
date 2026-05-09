package org.alexdev.unlimitednametags.hook;

import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.event.EventBus;
import com.lunarclient.apollo.event.player.ApolloRegisterPlayerEvent;
import com.lunarclient.apollo.event.player.ApolloUnregisterPlayerEvent;
import com.lunarclient.apollo.module.nametag.Nametag;
import com.lunarclient.apollo.module.nametag.NametagModule;
import com.lunarclient.apollo.player.ApolloPlayer;
import net.kyori.adventure.text.Component;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Integrates Lunar Client's Apollo nametag feature.
 *
 * We treat "Apollo player present" as "viewer is using Lunar Client with Apollo support".
 */
public final class ApolloHook extends Hook {

    private NametagModule nametagModule;
    private Consumer<ApolloRegisterPlayerEvent> registerListener;
    private Consumer<ApolloUnregisterPlayerEvent> unregisterListener;
    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> channelDetectedPlayers = ConcurrentHashMap.newKeySet();
    private Listener bukkitChannelListener;

    public ApolloHook(@NotNull UnlimitedNameTags plugin) {
        super(plugin);
    }

    @Override
    public void onEnable() {
        try {
            this.nametagModule = Apollo.getModuleManager().getModule(NametagModule.class);
            plugin.getLogger().info("Apollo found, enabling Apollo nametag integration");

            // When the viewer finishes Apollo handshake, push nametags for all currently tracked players.
            registerListener = event -> {
                final Object handle = event.getPlayer().getPlayer();
                if (!(handle instanceof Player viewer)) {
                    return;
                }
                readyPlayers.add(viewer.getUniqueId());
                plugin.getTaskScheduler().runTaskLaterAsynchronously(
                        () -> plugin.getNametagManager().updateDisplaysForPlayer(viewer), 2);
            };
            unregisterListener = event -> {
                final Object handle = event.getPlayer().getPlayer();
                if (!(handle instanceof Player viewer)) {
                    return;
                }
                readyPlayers.remove(viewer.getUniqueId());
                // Best-effort cleanup for this viewer.
                plugin.getTaskScheduler().runTask(() -> resetNametags(viewer));
            };

            EventBus.getBus().register(ApolloRegisterPlayerEvent.class, registerListener);
            EventBus.getBus().register(ApolloUnregisterPlayerEvent.class, unregisterListener);

            // Early detection: mark players as Apollo-capable as soon as they register the plugin channel.
            // This prevents spawning TextDisplays in the small window before Apollo finishes registering them.
            bukkitChannelListener = new Listener() {
                @EventHandler
                public void onRegisterChannel(PlayerRegisterChannelEvent event) {
                    if ("lunar:apollo".equalsIgnoreCase(event.getChannel())) {
                        channelDetectedPlayers.add(event.getPlayer().getUniqueId());
                    }
                }
            };
            Bukkit.getPluginManager().registerEvents(bukkitChannelListener, plugin);

            // If the plugin is reloaded while players are online, the register event won't re-fire.
            // Push nametags for currently connected Apollo players.
            plugin.getServer().getOnlinePlayers().forEach(p -> {
                if (isApolloPlayer(p)) {
                    if (Apollo.getPlayerManager().getPlayer(p.getUniqueId()).isPresent()) {
                        readyPlayers.add(p.getUniqueId());
                    }
                    plugin.getTaskScheduler().runTaskLaterAsynchronously(
                            () -> plugin.getNametagManager().updateDisplaysForPlayer(p), 2);
                }
            });
        } catch (Throwable t) {
            // Don't fail plugin enable if Apollo is present but something is off.
            this.nametagModule = null;
            plugin.getLogger().warning("Apollo detected but failed to initialize NametagModule: " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (registerListener != null) {
            EventBus.getBus().unregister(ApolloRegisterPlayerEvent.class, registerListener);
            registerListener = null;
        }
        if (unregisterListener != null) {
            EventBus.getBus().unregister(ApolloUnregisterPlayerEvent.class, unregisterListener);
            unregisterListener = null;
        }
        if (bukkitChannelListener != null) {
            HandlerList.unregisterAll(bukkitChannelListener);
            bukkitChannelListener = null;
        }
        readyPlayers.clear();
        channelDetectedPlayers.clear();
        this.nametagModule = null;
    }

    public boolean isReady() {
        return nametagModule != null;
    }

    /**
     * @return true if this player is connected with Apollo support (i.e. Lunar Client).
     */
    public boolean isApolloPlayer(@NotNull Player player) {
        if (!isReady()) {
            return false;
        }
        try {
            return channelDetectedPlayers.contains(player.getUniqueId()) || Apollo.getPlayerManager().hasSupport(player.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * @return true if the player is fully registered with Apollo (getPlayer() is present and register event fired).
     */
    public boolean isApolloReady(@NotNull Player player) {
        return isApolloPlayer(player) && readyPlayers.contains(player.getUniqueId());
    }

    public void overrideNametag(@NotNull Player viewer, @NotNull UUID targetUuid, @NotNull List<Component> lines) {
        if (!isReady()) {
            return;
        }

        final Optional<ApolloPlayer> apolloViewer = Apollo.getPlayerManager().getPlayer(viewer.getUniqueId());
        if (apolloViewer.isEmpty()) {
            return;
        }

        // Apollo calls should run on the server thread.
        plugin.getTaskScheduler().runTask(() -> nametagModule.overrideNametag(
                apolloViewer.get(),
                targetUuid,
                Nametag.builder().lines(lines).build()
        ));
    }

    public void resetNametag(@NotNull Player viewer, @NotNull UUID targetUuid) {
        if (!isReady()) {
            return;
        }

        final Optional<ApolloPlayer> apolloViewer = Apollo.getPlayerManager().getPlayer(viewer.getUniqueId());
        if (apolloViewer.isEmpty()) {
            return;
        }

        plugin.getTaskScheduler().runTask(() ->
                nametagModule.resetNametag(apolloViewer.get(), targetUuid));
    }

    public void resetNametags(@NotNull Player viewer) {
        if (!isReady()) {
            return;
        }

        final Optional<ApolloPlayer> apolloViewer = Apollo.getPlayerManager().getPlayer(viewer.getUniqueId());
        if (apolloViewer.isEmpty()) {
            return;
        }

        plugin.getTaskScheduler().runTask(() -> nametagModule.resetNametags(apolloViewer.get()));
    }
}
