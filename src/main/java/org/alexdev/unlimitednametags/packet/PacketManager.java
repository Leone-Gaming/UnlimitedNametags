package org.alexdev.unlimitednametags.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import me.tofaa.entitylib.APIConfig;
import me.tofaa.entitylib.EntityLib;
import me.tofaa.entitylib.spigot.SpigotEntityLibPlatform;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.data.ConcurrentMultimap;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class PacketManager {

    private final UnlimitedNameTags plugin;
    private final ConcurrentMultimap<UUID, Integer> passengers;
    private final ExecutorService executorService;

    public PacketManager(@NotNull UnlimitedNameTags plugin) {
        this.plugin = plugin;
        this.initialize();
        this.passengers = new ConcurrentMultimap<>();
        final ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("UnlimitedNameTags-PacketManager-%d")
                .build();
        this.executorService = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                namedThreadFactory
        );
    }

    private void initialize() {
        final SpigotEntityLibPlatform platform = new SpigotEntityLibPlatform(plugin);
        final APIConfig settings = new APIConfig(PacketEvents.getAPI())
                .usePlatformLogger();
        EntityLib.init(platform, settings);
    }

    public void close() {
        this.executorService.shutdown();
    }

    public void setPassengers(@NotNull Player player, @NotNull List<Integer> passengers) {
        this.passengers.replaceValues(player.getUniqueId(), passengers);
    }

    public void sendPassengersPacket(@NotNull User player, @NotNull PacketNameTag packetNameTag) {
        sendPassengersPacket(player, packetNameTag.getOwner(), List.of(packetNameTag.getEntityId()));
    }

    public void sendPassengersPacket(@NotNull User player, @NotNull Player owner, @NotNull Collection<Integer> extraPassengers) {
        final int ownerId = owner.getEntityId();
        if (player.getChannel() == null) {
            return;
        }

        final Collection<Integer> ownerPassengers = this.passengers.get(owner.getUniqueId());
        final Set<Integer> passengers = Sets.newHashSetWithExpectedSize(ownerPassengers.size() + extraPassengers.size());
        passengers.addAll(ownerPassengers);
        passengers.addAll(extraPassengers);
        final int[] passengersArray = passengers.stream().sorted().mapToInt(i -> i).toArray();
        final WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(ownerId, passengersArray);
        player.sendPacketSilently(packet);
    }

    public void removePassenger(@NotNull Player player, int passenger) {
        this.passengers.remove(player.getUniqueId(), passenger);
    }

    public int getEntityIndex() {
        return SpigotReflectionUtil.generateEntityId();
    }

    public void removePassenger(int passenger) {
        this.passengers.removeValueFromAll(passenger);
    }

    public void sendBundle(@NotNull User user, @NotNull Runnable runnable) {
        if (user == null || user.getChannel() == null) {
            return;
        }

        // Open bundle
        user.sendPacket(new WrapperPlayServerBundle());
        try {
            runnable.run();
        } catch (Exception ignored) {
        }
        // Close bundle
        user.sendPacket(new WrapperPlayServerBundle());
    }

}
