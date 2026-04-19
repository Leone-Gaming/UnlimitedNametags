package org.alexdev.unlimitednametags.nametags;

import com.github.Anon8281.universalScheduler.scheduling.tasks.MyScheduledTask;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.Getter;
import lombok.AccessLevel;
import lombok.Setter;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.config.Settings;
import org.alexdev.unlimitednametags.hook.HMCCosmeticsHook;
import org.alexdev.unlimitednametags.hook.ViaVersionHook;
import org.alexdev.unlimitednametags.packet.PacketNameTag;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Getter
public class NameTagManager {

    private static final class NameTagEntry {
        private final List<PacketNameTag> displays;

        private NameTagEntry(@NotNull PacketNameTag initial) {
            this.displays = new java.util.concurrent.CopyOnWriteArrayList<>();
            this.displays.add(initial);
        }

        @NotNull
        private PacketNameTag primary() {
            return displays.get(0);
        }

        @NotNull
        private List<PacketNameTag> all() {
            return Collections.unmodifiableList(displays);
        }
    }

    private final UnlimitedNameTags plugin;
    @Getter(AccessLevel.NONE)
    private final Map<UUID, NameTagEntry> nameTags;
    private final Map<Integer, PacketNameTag> entityIdToDisplay;
    private final Set<UUID> creating;
    private final Set<UUID> blocked;
    private final Set<UUID> hideNametags;
    private final Map<UUID, Settings.NameTag> nameTagOverrides;
    private final Map<UUID, Boolean> shiftSystemBlocked;
    private final List<MyScheduledTask> tasks;
    @Setter
    private boolean debug = false;
    private final Attribute scaleAttribute;

    public NameTagManager(@NotNull UnlimitedNameTags plugin) {
        this.plugin = plugin;
        this.nameTags = Maps.newConcurrentMap();
        this.entityIdToDisplay = Maps.newConcurrentMap();
        this.tasks = Lists.newCopyOnWriteArrayList();
        this.creating = Sets.newConcurrentHashSet();
        this.blocked = Sets.newConcurrentHashSet();
        this.hideNametags = Sets.newConcurrentHashSet();
        this.nameTagOverrides = Maps.newConcurrentMap();
        this.shiftSystemBlocked = Maps.newConcurrentMap();
        this.loadAll();
        this.scaleAttribute = loadScaleAttribute();
    }

    private void loadAll() {
        plugin.getTaskScheduler().runTaskLaterAsynchronously(() -> {
            plugin.getPlayerListener().getOnlinePlayers().values().forEach(p -> addPlayer(p, true));
            this.startTask();
        }, 5);
    }

    private void startTask() {
        tasks.forEach(MyScheduledTask::cancel);
        final MyScheduledTask refresh = plugin.getTaskScheduler().runTaskTimerAsynchronously(
                () -> {
                    if (plugin.isPaper() && plugin.getServer().isStopping()) {
                        return;
                    }
                    nameTags.values().forEach(entry -> refresh(entry.primary().getOwner(), false));
                },
                10, plugin.getConfigManager().getSettings().getTaskInterval());

        // Refresh passengers
        final MyScheduledTask passengers = plugin.getTaskScheduler().runTaskTimerAsynchronously(() -> nameTags.values()
                .stream()
                .map(entry -> entry.primary().getOwner())
                .filter(p -> plugin.getHook(HMCCosmeticsHook.class).map(h -> !h.hasBackpack(p)).orElse(true))
                .forEach(player -> getPacketDisplayText(player).ifPresent(PacketNameTag::sendPassengerPacketToViewers)),
                20, 20 * 5L);

        // Scale task
        if (isScalePresent()) {
            final MyScheduledTask scale = plugin.getTaskScheduler()
                    .runTaskTimerAsynchronously(() -> nameTags.values().forEach(entry -> entry.all().forEach(tag -> {
                        if (tag.checkScale()) {
                            tag.refresh();
                        }
                    })), 20, 10);
            tasks.add(scale);
        }

        if (plugin.getConfigManager().getSettings().isShowWhileLooking()) {
            final MyScheduledTask point = plugin.getTaskScheduler().runTaskTimerAsynchronously(() -> {
                nameTags.values().forEach(entry -> {
                    final PacketNameTag primary = entry.primary();
                    final Player targetOwner = primary.getOwner();
                    final List<Player> viewers = plugin.getTrackerManager().getWhoTracks(targetOwner);

                    for (Player viewer : viewers) {
                        if (plugin.getHook(ViaVersionHook.class).map(h -> h.hasNotTextDisplays(viewer)).orElse(false)) {
                            continue;
                        }

                        final boolean isPointing = isPlayerPointingAt(viewer, targetOwner);

                        if (primary.canPlayerSee(viewer) && !isPointing) {
                            entry.all().forEach(d -> d.hideFromPlayer(viewer));
                        } else if (!primary.canPlayerSee(viewer) && isPointing) {
                            entry.all().forEach(d -> d.showToPlayer(viewer));
                        }
                    }
                });
            }, 5, 5);
            tasks.add(point);
        }

        tasks.add(refresh);
        tasks.add(passengers);
    }

    private boolean isPerLineEntitiesMode() {
        return plugin.getConfigManager().getSettings().getTextDisplayMode() == Settings.TextDisplayMode.PER_LINE_ENTITIES;
    }

    @NotNull
    public List<PacketNameTag> getPacketDisplayTexts(@NotNull Player player) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry == null) {
            return List.of();
        }
        return entry.all();
    }

    @NotNull
    private Set<UUID> getViewersUnion(@NotNull NameTagEntry entry) {
        final Set<UUID> viewers = new HashSet<>();
        entry.all().forEach(d -> viewers.addAll(d.getViewers()));
        return viewers;
    }

    private Attribute loadScaleAttribute() {
        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_20_5)) {
            return null;
        }
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThan(ServerVersion.V_1_21_1)) {
            return Attribute.SCALE;
        } else {
            return Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.scale"));
        }
    }

    public boolean isPlayerPointingAt(Player player1, Player player2) {
        if (player1.getWorld() != player2.getWorld()) {
            return false;
        }

        if (player1.getLocation().distance(player2.getLocation()) < 5) {
            return true;
        }

        final org.bukkit.util.Vector direction = player1.getEyeLocation().getDirection();
        final Vector toPlayer2 = player2.getEyeLocation().toVector().subtract(player1.getEyeLocation().toVector());
        toPlayer2.normalize();

        final double dotProduct = direction.dot(toPlayer2);
        return dotProduct > 0.90;
    }

    public boolean isScalePresent() {
        return PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_20_5);
    }

    /**
     * Compatibility getter: exposes the primary (first) display entity per player.
     * Internal code should prefer {@link #getPacketDisplayTexts(Player)} when it needs all line entities.
     */
    @NotNull
    public Map<UUID, PacketNameTag> getNameTags() {
        final Map<UUID, PacketNameTag> result = new HashMap<>();
        nameTags.forEach((uuid, entry) -> result.put(uuid, entry.primary()));
        return Collections.unmodifiableMap(result);
    }

    public float getScale(@NotNull Player player) {
        if (!isScalePresent()) {
            return 1;
        }

        if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThan(ServerVersion.V_1_20_5)) {
            return 1;
        }

        final AttributeInstance attribute = player.getAttribute(scaleAttribute);

        if (attribute == null) {
            return 1;
        }

        return (int) attribute.getValue();
    }

    public void blockPlayer(@NotNull Player player) {
        blocked.add(player.getUniqueId());
        if (debug) {
            plugin.getLogger().info("Blocked " + player.getName());
        }
    }

    public void unblockPlayer(@NotNull Player player) {
        blocked.remove(player.getUniqueId());
        if (debug) {
            plugin.getLogger().info("Unblocked " + player.getName());
        }
    }

    public void clearCache(@NotNull UUID uuid) {
        blocked.remove(uuid);
        creating.remove(uuid);
        hideNametags.remove(uuid);
        nameTagOverrides.remove(uuid);
        shiftSystemBlocked.remove(uuid);
    }

    public boolean hasNametagOverride(@NotNull Player player) {
        return nameTagOverrides.containsKey(player.getUniqueId());
    }

    @NotNull
    public Optional<Settings.NameTag> getNametagOverride(@NotNull Player player) {
        return Optional.ofNullable(nameTagOverrides.get(player.getUniqueId()));
    }

    @NotNull
    public Settings.NameTag getEffectiveNametag(@NotNull Player player) {
        return nameTagOverrides.getOrDefault(player.getUniqueId(),
                plugin.getConfigManager().getSettings().getNametag(player));
    }

    @NotNull
    public Settings.NameTag getConfigNametag(@NotNull Player player) {
        return plugin.getConfigManager().getSettings().getNametag(player);
    }

    private boolean preAddChecks(@NotNull Player player, boolean canBlock) {
        if (nameTags.containsKey(player.getUniqueId())) {
            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " already has a nametag");
            }
            return false;
        }

        if (creating.contains(player.getUniqueId())) {
            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " is already creating a nametag");
            }
            return false;
        }

        if (blocked.contains(player.getUniqueId())) {
            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " is blocked");
            }
            return false;
        } else {
            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " is not blocked");
            }
        }

        if (PacketEvents.getAPI().getPlayerManager().getUser(player) == null) {
            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " is not loaded");
            }
            return false;
        }

        if (player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " has invisibility potion effect, blocking");
            }
            if (canBlock) {
                blockPlayer(player);
            }
            return false;
        }

        if (player.getGameMode() == GameMode.SPECTATOR) {
            if (debug) {
                plugin.getLogger().info("Player " + player.getName() + " is in spectator mode, skipping");
            }
            if (canBlock) {
                blockPlayer(player);
            }
            return false;
        }

        return true;
    }

    public void addPlayer(@NotNull Player player, boolean canBlock) {
        if (!preAddChecks(player, canBlock)) {
            return;
        }

        creating.add(player.getUniqueId());

        final Settings.NameTag nametag = getEffectiveNametag(player);
        final PacketNameTag display = new PacketNameTag(plugin, player, nametag);
        final NameTagEntry entry = new NameTagEntry(display);
        display.text(player, Component.empty());
        display.spawn(player);

        if (plugin.getConfigManager().getSettings().isShowCurrentNameTag()) {
            display.showToPlayer(player);
        }

        handleVanish(player, display);

        nameTags.put(player.getUniqueId(), entry);
        if (debug) {
            plugin.getLogger().info("Added nametag for " + player.getName());
        }
        entityIdToDisplay.put(display.getEntityId(), display);

        if (isPerLineEntitiesMode()) {
            plugin.getPlaceholderManager().applyPlaceholdersLines(player, nametag.linesGroups(), List.of(player))
                    .thenAccept(lines -> loadDisplayLines(player, lines.get(player), nametag, entry))
                    .exceptionally(throwable -> {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "Failed to create nametag for " + player.getName(), throwable);
                        creating.remove(player.getUniqueId());
                        return null;
                    });
            return;
        }

        plugin.getPlaceholderManager().applyPlaceholders(player, nametag.linesGroups(), List.of(player))
                .thenAccept(lines -> loadDisplay(player, lines.get(player), nametag, display))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Failed to create nametag for " + player.getName(), throwable);
                    creating.remove(player.getUniqueId());
                    return null;
                });
    }

    public void refresh(@NotNull Player player, boolean force) {
        final Settings.NameTag nametag = getEffectiveNametag(player);

        if (PacketEvents.getAPI().getPlayerManager().getUser(player) == null) {
            return;
        }

        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry == null) {
            return;
        }
        final PacketNameTag display = entry.primary();

        final boolean show = plugin.getConfigManager().getSettings().isShowCurrentNameTag();
        if (show && !display.canPlayerSee(player)) {
            getPacketDisplayTexts(player).forEach(d -> d.showToPlayer(player));
        } else if (!show && display.canPlayerSee(player)) {
            getPacketDisplayTexts(player).forEach(d -> d.hideFromPlayer(player));
        }

        if (force) {
            if (show) {
                getPacketDisplayTexts(player).forEach(d -> d.showToPlayer(display.getOwner()));
            } else {
                getPacketDisplayTexts(player).forEach(d -> d.hideFromPlayer(display.getOwner()));
            }
        }

        final List<Player> relationalPlayers = getViewersUnion(entry).stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();

        if (isPerLineEntitiesMode()) {
            final List<Player> withOwner = new ArrayList<>(relationalPlayers.size() + 1);
            withOwner.add(player); // always include owner so line-count changes still allocate entities
            withOwner.addAll(relationalPlayers.stream().filter(p -> !p.equals(player)).toList());
            plugin.getPlaceholderManager().applyPlaceholdersLines(player, nametag.linesGroups(), withOwner)
                    .thenAccept(lines -> editDisplayLines(player, lines, nametag, force))
                    .exceptionally(throwable -> {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "Failed to edit nametag for " + player.getName(), throwable);
                        return null;
                    });
            return;
        }

        plugin.getPlaceholderManager().applyPlaceholders(player, nametag.linesGroups(), relationalPlayers)
                .thenAccept(lines -> editDisplay(player, lines, nametag, force))
                .exceptionally(throwable -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Failed to edit nametag for " + player.getName(), throwable);
                    return null;
                });
    }

    private void editDisplay(@NotNull Player player, Map<Player, Component> components,
            @NotNull Settings.NameTag nameTag, boolean force) {
        getPacketDisplayText(player).ifPresent(packetNameTag -> {
            if (!packetNameTag.getNameTag().equals(nameTag)) {
                packetNameTag.setNameTag(nameTag);
            }
            if (force && isScalePresent()) {
                packetNameTag.checkScale();
            }

            final boolean shadowed = nameTag.background().shadowed();
            final boolean seeThrough = nameTag.background().seeThrough() && !packetNameTag.isSneaking();
            final int backgroundColor = nameTag.background().getColor().asARGB();

            components.forEach((p, c) -> {
                final boolean[] updateRef = { packetNameTag.text(p, c) || force };
                final User user = PacketEvents.getAPI().getPlayerManager().getUser(p);
                if (user == null) {
                    return;
                }
                packetNameTag.modify(user, m -> {

                    if (force) {
                        m.setShadow(shadowed);
                        m.setSeeThrough(seeThrough);
                        m.setBackgroundColor(backgroundColor);
                    } else {
                        if (m.isShadow() != shadowed) {
                            m.setShadow(shadowed);
                            updateRef[0] = true;
                        }
                        if (m.isSeeThrough() != seeThrough) {
                            m.setSeeThrough(seeThrough);
                            updateRef[0] = true;
                        }
                    }

                });

                if (updateRef[0]) {
                    packetNameTag.refreshForPlayer(p);
                }
            });
        });
    }

    private void editDisplayLines(@NotNull Player player, Map<Player, List<Component>> components,
            @NotNull Settings.NameTag nameTag, boolean force) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry == null) {
            return;
        }

        final float viewRange = plugin.getConfigManager().getSettings().getViewDistance();
        final AbstractDisplayMeta.BillboardConstraints billboard = plugin.getConfigManager().getSettings()
                .getDefaultBillboard();
        final float baseYOffset = plugin.getConfigManager().getSettings().getYOffset();
        final float spacingBase = plugin.getConfigManager().getSettings().getPerLineEntitySpacing();

        // Make sure we have enough entities to satisfy the maximum line count across viewers.
        final int maxLines = Math.max(1, components.values().stream().mapToInt(List::size).max().orElse(1));
        ensureDisplayCount(player, nameTag, entry, maxLines);
        updateLineOffsets(entry, baseYOffset, spacingBase);

        // Keep base metadata consistent so newly-spawned per-player entities inherit the current settings.
        entry.all().forEach(d -> {
            d.modify(m -> m.setUseDefaultBackground(false));
            d.setBillboard(billboard);
            d.setViewRange(viewRange);
        });

        final boolean shadowed = nameTag.background().shadowed();
        final int backgroundColor = nameTag.background().getColor().asARGB();

        // Update per-viewer text (and show/hide line entities depending on the viewer's line count).
        components.forEach((viewer, viewerLines) -> {
            final User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
            if (user == null) {
                return;
            }

            final int viewerLineCount = viewerLines.size();
            final int slotCount = entry.all().size();
            final int slotStart = Math.max(0, slotCount - viewerLineCount); // bottom-align viewer lines into the stack
            final float spacing = spacingBase * entry.primary().getScale();
            boolean anyNewlyShown = false;

            for (int slot = 0; slot < slotCount; slot++) {
                final PacketNameTag lineDisplay = entry.all().get(slot);
                if (!lineDisplay.getNameTag().equals(nameTag)) {
                    lineDisplay.setNameTag(nameTag);
                }
                if (force && isScalePresent()) {
                    lineDisplay.checkScale();
                }

                final int lineIndex = slot - slotStart;
                final boolean shouldShowLine = lineIndex >= 0 && lineIndex < viewerLineCount;

                if (!shouldShowLine) {
                    if (lineDisplay.canPlayerSee(viewer)) {
                        lineDisplay.hideFromPlayer(viewer);
                    }
                    continue;
                }

                final boolean wasVisible = lineDisplay.canPlayerSee(viewer);
                if (!wasVisible) {
                    lineDisplay.showToPlayer(viewer);
                    // The viewer entity might be created after updateLineOffsets() ran, so re-apply the offset.
                    final float expectedOffset = baseYOffset + ((slotCount - 1 - slot) * spacing);
                    lineDisplay.resetOffset(expectedOffset);
                    anyNewlyShown = true;
                }

                final Component lineComponent = viewerLines.get(lineIndex);
                final boolean[] updateRef = { lineDisplay.text(viewer, lineComponent) || force };

                final boolean seeThrough = nameTag.background().seeThrough() && !lineDisplay.isSneaking();
                lineDisplay.modify(user, m -> {
                    if (force) {
                        m.setShadow(shadowed);
                        m.setSeeThrough(seeThrough);
                        m.setBackgroundColor(backgroundColor);
                    } else {
                        if (m.isShadow() != shadowed) {
                            m.setShadow(shadowed);
                            updateRef[0] = true;
                        }
                        if (m.isSeeThrough() != seeThrough) {
                            m.setSeeThrough(seeThrough);
                            updateRef[0] = true;
                        }
                    }

                    // Keep opacity consistent; otherwise newly created line entities can end up invisible.
                    m.setTextOpacity((byte) (lineDisplay.isSneaking()
                            ? plugin.getConfigManager().getSettings().getSneakOpacity()
                            : -1));
                    updateRef[0] = true;
                });

                if (updateRef[0]) {
                    lineDisplay.refreshForPlayer(viewer);
                }
            }

            // Keep the passenger list accurate for this viewer (important when the line count changes).
            entry.primary().sendPassengersPacket(user);

            if (anyNewlyShown) {
                final List<Component> viewerLinesSnapshot = List.copyOf(viewerLines);
                final int slotCountSnapshot = slotCount;
                final int slotStartSnapshot = slotStart;
                plugin.getTaskScheduler().runTaskLaterAsynchronously(() -> {
                    final Player currentViewer = plugin.getPlayerListener().getPlayer(viewer.getUniqueId());
                    if (currentViewer == null) {
                        return;
                    }
                    final User currentUser = PacketEvents.getAPI().getPlayerManager().getUser(currentViewer);
                    if (currentUser == null) {
                        return;
                    }
                    final NameTagEntry currentEntry = nameTags.get(player.getUniqueId());
                    if (currentEntry == null) {
                        return;
                    }

                    for (int slot = 0; slot < slotCountSnapshot; slot++) {
                        final int lineIndex = slot - slotStartSnapshot;
                        final boolean shouldShowLine = lineIndex >= 0 && lineIndex < viewerLinesSnapshot.size();
                        final PacketNameTag display = currentEntry.all().get(slot);
                        if (!shouldShowLine) {
                            continue;
                        }
                        display.text(currentViewer, viewerLinesSnapshot.get(lineIndex));
                        display.modify(currentUser, m -> m.setTextOpacity((byte) (display.isSneaking()
                                ? plugin.getConfigManager().getSettings().getSneakOpacity()
                                : -1)));
                        display.refreshForPlayer(currentViewer);
                    }

                    currentEntry.primary().sendPassengersPacket(currentUser);
                }, 1);
            }
        });
    }

    private void loadDisplay(@NotNull Player player, @NotNull Component component,
            @NotNull Settings.NameTag nameTag,
            @NotNull PacketNameTag display) {
        try {
            final Location location = player.getLocation().clone();
            // add 1.80 to make a perfect tp animation
            location.setY(location.getY() + 1.80);

            creating.remove(player.getUniqueId());
            display.modify(m -> m.setUseDefaultBackground(false));
            display.text(player, component);
            display.setBillboard(plugin.getConfigManager().getSettings().getDefaultBillboard());
            display.setShadowed(nameTag.background().shadowed());
            display.setSeeThrough(nameTag.background().seeThrough() && !display.isSneaking());
            // background color, if disabled, set to transparent
            display.setBackgroundColor(nameTag.background().getColor());

            display.resetOffset(plugin.getConfigManager().getSettings().getYOffset());

            display.setViewRange(plugin.getConfigManager().getSettings().getViewDistance());

            plugin.getTaskScheduler().runTaskLater(() -> display.modifyOwner(meta -> meta.setText(component)), 1);

            display.refresh();

            handleVanish(player, display);

        } catch (Throwable e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to create nametag for " + player.getName(),
                    e);
        }
    }

    private void loadDisplayLines(@NotNull Player player, @NotNull List<Component> lines,
            @NotNull Settings.NameTag nameTag,
            @NotNull NameTagEntry entry) {
        try {
            final Location location = player.getLocation().clone();
            // add 1.80 to make a perfect tp animation
            location.setY(location.getY() + 1.80);

            creating.remove(player.getUniqueId());

            final int count = Math.max(1, lines.size());
            ensureDisplayCount(player, nameTag, entry, count);
            updateLineOffsets(entry, plugin.getConfigManager().getSettings().getYOffset(),
                    plugin.getConfigManager().getSettings().getPerLineEntitySpacing());

            for (int i = 0; i < entry.all().size(); i++) {
                final PacketNameTag display = entry.all().get(i);
                display.modify(m -> m.setUseDefaultBackground(false));
                display.text(player, i < lines.size() ? lines.get(i) : Component.empty());
                display.setBillboard(plugin.getConfigManager().getSettings().getDefaultBillboard());
                display.setShadowed(nameTag.background().shadowed());
                display.setSeeThrough(nameTag.background().seeThrough() && !display.isSneaking());
                display.setBackgroundColor(nameTag.background().getColor());
                display.setViewRange(plugin.getConfigManager().getSettings().getViewDistance());

                final Component ownerText = i < lines.size() ? lines.get(i) : Component.empty();
                plugin.getTaskScheduler().runTaskLater(() -> display.modifyOwner(meta -> meta.setText(ownerText)), 1);

                display.refresh();
            }

            if (plugin.getConfigManager().getSettings().isShowCurrentNameTag()) {
                entry.all().forEach(d -> d.showToPlayer(player));
            }

            entry.all().forEach(d -> handleVanish(player, d));
        } catch (Throwable e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to create nametag for " + player.getName(),
                    e);
        }
    }

    private void ensureDisplayCount(@NotNull Player owner, @NotNull Settings.NameTag nameTag, @NotNull NameTagEntry entry,
            int count) {
        final int target = Math.max(1, count);

        while (entry.displays.size() < target) {
            final PacketNameTag added = new PacketNameTag(plugin, owner, nameTag);
            // New line entities must be eligible to show; otherwise showToPlayer() is a no-op.
            added.setVisible(entry.primary().isVisible());
            // Keep new line entities consistent with current sneak state.
            added.setSneaking(entry.primary().isSneaking());
            entry.displays.add(added);
            entityIdToDisplay.put(added.getEntityId(), added);
            // Spawn for owner so future showToPlayer has a base entity instance to copy from.
            added.text(owner, Component.empty());
            added.spawn(owner);
            // Ensure the new line is visible; some impls default TextDisplay opacity to 0.
            added.setTextOpacity((byte) (added.isSneaking()
                    ? plugin.getConfigManager().getSettings().getSneakOpacity()
                    : -1));
        }

        while (entry.displays.size() > target) {
            final PacketNameTag removed = entry.displays.remove(entry.displays.size() - 1);
            entityIdToDisplay.remove(removed.getEntityId());
            removed.remove();
        }
    }

    private void updateLineOffsets(@NotNull NameTagEntry entry, float baseYOffset, float spacingBase) {
        final int count = entry.all().size();
        final float spacing = spacingBase * entry.primary().getScale();
        for (int i = 0; i < count; i++) {
            final PacketNameTag display = entry.all().get(i);
            // Bottom-anchored stacking (grows upward): last line stays at baseYOffset, earlier lines go up.
            // This keeps the nametag from dropping lower when more lines are added.
            display.resetOffset(baseYOffset + ((count - 1 - i) * spacing));
        }
    }

    private void handleVanish(@NotNull Player player, @NotNull PacketNameTag display) {
        final boolean isVanished = plugin.getVanishManager().isVanished(player);

        // if player is vanished, hide display for all players except for who can see
        // the player
        plugin.getPlayerListener().getOnlinePlayers().values().stream()
                .filter(p -> p != player)
                .filter(p -> p.getLocation().getWorld() == player.getLocation().getWorld())
                .filter(p -> !isVanished || plugin.getVanishManager().canSee(p, player))
                .filter(p -> p.getLocation().distance(player.getLocation()) <= 250)
                .filter(p -> !display.canPlayerSee(p))
                .forEach(display::showToPlayer);
    }

    public void removePlayer(@NotNull Player player) {
        final NameTagEntry entry = nameTags.remove(player.getUniqueId());
        if (entry != null) {
            entry.all().forEach(tag -> {
                tag.remove();
                entityIdToDisplay.remove(tag.getEntityId());
            });
        }

        nameTags.forEach((uuid, display) -> {
            display.all().forEach(tag -> {
                tag.handleQuit(player);
                tag.getBlocked().remove(player.getUniqueId());
            });
        });
    }

    public void removeAllViewers(@NotNull Player player) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry != null) {
            entry.all().forEach(tag -> {
                tag.setVisible(false);
                tag.clearViewers();
            });
        }
    }

    public void showToTrackedPlayers(@NotNull Player player) {
        showToTrackedPlayers(player, plugin.getTrackerManager().getWhoTracks(player));
    }

    public void showToTrackedPlayers(@NotNull Player player, @NotNull Collection<Player> tracked) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry != null) {
            entry.all().forEach(tag -> tag.setVisible(true));
            final Set<Player> players = tracked.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            players.add(entry.primary().getOwner());
            entry.all().forEach(tag -> tag.showToPlayers(players));
            if (debug) {
                plugin.getLogger().info("Showing nametag of " + player.getName() + " to tracked players: " +
                        players.stream().map(Player::getName).collect(Collectors.joining(", ")));
            }
            return;
        }

        addPlayer(player, false);
    }

    public void hideAllDisplays(@NotNull Player player) {
        nameTags.forEach((uuid, display) -> {
            display.all().forEach(tag -> {
                tag.hideFromPlayer(player);
                tag.getBlocked().add(player.getUniqueId());
            });
        });
        getPacketDisplayTexts(player).forEach(PacketNameTag::clearViewers);
    }

    public void removeAll() {
        nameTags.forEach((uuid, entry) -> entry.all().forEach(PacketNameTag::remove));

        entityIdToDisplay.clear();
        nameTags.clear();
    }

    public void updateSneaking(@NotNull Player player, boolean sneaking) {
        if (shiftSystemBlocked.getOrDefault(player.getUniqueId(), false)) {
            return;
        }

        getPacketDisplayTexts(player).forEach(packetNameTag -> {
            if (packetNameTag.getNameTag().background().seeThrough()) {
                packetNameTag.setSeeThrough(!sneaking);
            }

            packetNameTag.setSneaking(sneaking);
            packetNameTag
                    .setTextOpacity((byte) (sneaking ? plugin.getConfigManager().getSettings().getSneakOpacity() : -1));
            packetNameTag.refresh();
        });
    }

    public void reload() {
        final float yOffset = plugin.getConfigManager().getSettings().getYOffset();
        final float viewDistance = plugin.getConfigManager().getSettings().getViewDistance();
        final AbstractDisplayMeta.BillboardConstraints billboard = plugin.getConfigManager().getSettings()
                .getDefaultBillboard();

        plugin.getTaskScheduler()
                .runTaskAsynchronously(() -> plugin.getPlayerListener().getOnlinePlayers().values().forEach(p -> {
                    setYOffset(p, yOffset);
                    setViewDistance(p, viewDistance);
                    setBillBoard(p, billboard);
                    refresh(p, true);
                }));
        startTask();
    }

    @SuppressWarnings("UnstableApiUsage")
    public void debug(@NotNull CommandSender audience) {
        audience.sendRichMessage("<red>UnlimitedNameTags v" + plugin.getPluginMeta().getVersion() + " . Compiled: "
                + plugin.getConfigManager().isCompiled());
        final AtomicReference<Component> component = new AtomicReference<>(
                Component.text("Nametags:").colorIfAbsent(TextColor.color(0xFF0000)));
        nameTags.forEach((uuid, display) -> {
            final Player player = plugin.getPlayerListener().getPlayer(uuid);

            if (player == null) {
                return;
            }

            final List<String> viewers = getViewersUnion(display).stream()
                    .map(Bukkit::getPlayer)
                    .filter(Objects::nonNull)
                    .map(Player::getName)
                    .collect(Collectors.toList());

            if (!plugin.getConfigManager().getSettings().isShowCurrentNameTag()) {
                viewers.remove(player.getName());
            }

            final PacketNameTag primary = display.primary();
            final long lastUpdate = primary.getLastUpdate();

            final Component text = getComponent(primary, viewers, player, lastUpdate);
            component.set(component.get().append(Component.text("\n")).append(text));
        });

        plugin.getKyoriManager().sendMessage(audience, component.get());
    }

    @NotNull
    private Component getComponent(@NotNull PacketNameTag display, @NotNull List<String> viewers,
            @NotNull Player player, long lastUpdate) {
        final int seconds = (int) ((System.currentTimeMillis() - lastUpdate) / 1000);
        final Map<String, String> properties = display.properties();
        Component hover = Component.text("Viewers: " + viewers).appendNewline()
                .append(Component.text("Owner: " + display.getOwner().getName())).appendNewline()
                .append(Component.text("Visible: " + display.isVisible())).appendNewline()
                .append(Component.text("Last update: " + seconds + "s ago")).appendNewline();

        for (Map.Entry<String, String> entry : properties.entrySet()) {
            hover = hover.append(Component.text(entry.getKey() + ": " + entry.getValue())).appendNewline();
        }

        Component text = Component.text(player.getName() + " -> " + " " + display.getEntityId());
        text = text.color(TextColor.color(0x00FF00));
        text = text.hoverEvent(hover.color(TextColor.color(Color.RED.asRGB())));
        return text;
    }

    private void setYOffset(@NotNull Player player, float yOffset) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry == null) {
            return;
        }
        if (isPerLineEntitiesMode()) {
            updateLineOffsets(entry, yOffset, plugin.getConfigManager().getSettings().getPerLineEntitySpacing());
        } else {
            entry.primary().resetOffset(yOffset);
        }
    }

    private void setBillBoard(@NotNull Player player, AbstractDisplayMeta.BillboardConstraints billboard) {
        getPacketDisplayTexts(player).forEach(packetNameTag -> packetNameTag.setBillboard(billboard));
    }

    private void setViewDistance(@NotNull Player player, float viewDistance) {
        getPacketDisplayTexts(player).forEach(packetNameTag -> packetNameTag.setViewRange(viewDistance));
    }

    public void vanishPlayer(@NotNull Player player) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry == null) {
            return;
        }
        final Set<UUID> viewers = new HashSet<>(getViewersUnion(entry));
        final boolean isVanished = plugin.getVanishManager().isVanished(player);
        viewers.forEach(uuid -> {
            final Player viewer = plugin.getPlayerListener().getPlayer(uuid);
            if (viewer == null || viewer == player) {
                return;
            }
            if (isVanished && !plugin.getVanishManager().canSee(viewer, player)) {
                return;
            }
            entry.all().forEach(tag -> tag.hideFromPlayer(viewer));
        });
    }

    public void unVanishPlayer(@NotNull Player player) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry == null) {
            return;
        }
        final Set<UUID> viewers = new HashSet<>(getViewersUnion(entry));
        viewers.forEach(uuid -> {
            final Player viewer = plugin.getPlayerListener().getPlayer(uuid);
            if (viewer == null || viewer == player) {
                return;
            }
            entry.all().forEach(tag -> tag.showToPlayer(viewer));
        });
    }

    @NotNull
    public Optional<PacketNameTag> getPacketDisplayText(@NotNull Player player) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        return entry == null ? Optional.empty() : Optional.of(entry.primary());
    }

    @NotNull
    public Optional<PacketNameTag> getPacketDisplayText(int id) {
        return Optional.ofNullable(entityIdToDisplay.get(id));
    }

    public void updateDisplay(@NotNull Player player, @NotNull Player target) {
        if (player == target && plugin.getConfigManager().getSettings().isShowCurrentNameTag()) {
            showToOwner(player);
            return;
        }
        getPacketDisplayTexts(target).forEach(packetNameTag -> {
            packetNameTag.hideFromPlayerSilently(player);
            packetNameTag.showToPlayer(player);
        });
    }

    public void showToOwner(@NotNull Player player) {
        if (!plugin.getConfigManager().getSettings().isShowCurrentNameTag()) {
            return;
        }
        getPacketDisplayTexts(player).forEach(PacketNameTag::spawnForOwner);
    }

    public void removeDisplay(@NotNull Player player, @NotNull Player target) {
        if (player == target && !plugin.getConfigManager().getSettings().isShowCurrentNameTag()) {
            return;
        }
        getPacketDisplayTexts(target).forEach(packetNameTag -> packetNameTag.hideFromPlayer(player));
    }

    public void updateDisplaysForPlayer(@NotNull Player player) {
        nameTags.forEach((uuid, display) -> {
            final Player owner = display.primary().getOwner();
            if (owner == player) {
                return;
            }
            final Set<UUID> tracked = plugin.getTrackerManager().getTrackedPlayers(owner.getUniqueId());

            if (player.getLocation().getWorld() != owner.getLocation().getWorld()) {
                return;
            }

            if (plugin.getVanishManager().isVanished(owner) && !plugin.getVanishManager().canSee(player, owner)) {
                return;
            }

            if (!tracked.contains(player.getUniqueId())) {
                return;
            }

            display.all().forEach(tag -> {
                tag.getBlocked().remove(player.getUniqueId());
                tag.hideFromPlayerSilently(player);
                tag.showToPlayer(player);
            });
        });
    }

    public void refreshDisplaysForPlayer(@NotNull Player player) {
        nameTags.forEach((uuid, display) -> {
            if (!display.primary().canPlayerSee(player)) {
                return;
            }

            display.all().forEach(tag -> tag.refreshForPlayer(player));
        });
    }

    public void unBlockForAllPlayers(@NotNull Player player) {
        nameTags.forEach((uuid, display) -> display.all().forEach(tag -> tag.getBlocked().remove(player.getUniqueId())));
    }

    public void hideOtherNametags(@NotNull Player player) {
        hideNametags.add(player.getUniqueId());
        nameTags.forEach((uuid, display) -> {
            if (display.primary().canPlayerSee(player)) {
                display.all().forEach(tag -> tag.hideFromPlayer(player));
            }
        });
    }

    public void showOtherNametags(@NotNull Player player) {
        hideNametags.remove(player.getUniqueId());
        plugin.getTrackerManager().getTrackedPlayers(player.getUniqueId()).forEach(uuid -> {
            final Player tracked = plugin.getPlayerListener().getPlayer(uuid);
            if (tracked == null) {
                return;
            }

            getPacketDisplayTexts(tracked).forEach(display -> display.showToPlayer(player));
        });
    }

    public boolean isHiddenOtherNametags(@NotNull Player player) {
        return hideNametags.contains(player.getUniqueId());
    }

    public void swapNametag(@NotNull Player player, @NotNull Settings.NameTag nameTag) {
        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry == null) {
            return;
        }

        entry.all().forEach(d -> d.setNameTag(nameTag));

        final List<Player> relationalPlayers = getViewersUnion(entry).stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();

        if (isPerLineEntitiesMode()) {
            final List<Player> withOwner = new ArrayList<>(relationalPlayers.size() + 1);
            withOwner.add(player);
            withOwner.addAll(relationalPlayers.stream().filter(p -> !p.equals(player)).toList());
            plugin.getPlaceholderManager().applyPlaceholdersLines(player, nameTag.linesGroups(), withOwner)
                    .thenAccept(lines -> editDisplayLines(player, lines, nameTag, true))
                    .exceptionally(throwable -> {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "Failed to swap nametag for " + player.getName(), throwable);
                        return null;
                    });
            return;
        }

        plugin.getPlaceholderManager().applyPlaceholders(player, nameTag.linesGroups(), relationalPlayers)
                .thenAccept(lines -> {
                    final PacketNameTag display = entry.primary();
                    final Component component = lines.get(player);
                    display.text(player, component);
                    display.setBillboard(plugin.getConfigManager().getSettings().getDefaultBillboard());
                    display.setShadowed(nameTag.background().shadowed());
                    display.setSeeThrough(nameTag.background().seeThrough() && !display.isSneaking());
                    display.setBackgroundColor(nameTag.background().getColor());
                    display.resetOffset(plugin.getConfigManager().getSettings().getYOffset());
                    display.setViewRange(plugin.getConfigManager().getSettings().getViewDistance());

                    plugin.getTaskScheduler().runTaskLater(() -> display.modifyOwner(meta -> meta.setText(component)),
                            1);

                    lines.forEach((p, c) -> {
                        if (!p.equals(player)) {
                            display.text(p, c);
                        }
                    });

                    display.refresh();
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Failed to swap nametag for " + player.getName(), throwable);
                    return null;
                });
    }

    public void setNametagOverride(@NotNull Player player, @NotNull Settings.NameTag nameTag) {
        nameTagOverrides.put(player.getUniqueId(), nameTag);

        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry != null) {
            swapNametag(player, nameTag);
        }
    }

    public void removeNametagOverride(@NotNull Player player) {
        nameTagOverrides.remove(player.getUniqueId());

        final NameTagEntry entry = nameTags.get(player.getUniqueId());
        if (entry != null) {
            final Settings.NameTag configNametag = getConfigNametag(player);
            swapNametag(player, configNametag);
        }
    }

    public void setShiftSystemBlocked(@NotNull Player player, boolean blocked) {
        if (blocked) {
            shiftSystemBlocked.put(player.getUniqueId(), true);
        } else {
            shiftSystemBlocked.remove(player.getUniqueId());
        }
    }

    public boolean isShiftSystemBlocked(@NotNull Player player) {
        return shiftSystemBlocked.getOrDefault(player.getUniqueId(), false);
    }
}
