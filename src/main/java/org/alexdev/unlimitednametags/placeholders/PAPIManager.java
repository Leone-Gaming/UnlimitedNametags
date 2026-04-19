package org.alexdev.unlimitednametags.placeholders;

import lombok.Getter;
import me.clip.placeholderapi.PlaceholderAPI;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

@RequiredArgsConstructor
public class PAPIManager {

    private final UnlimitedNameTags plugin;
    @Getter
    private boolean papiEnabled;
    private UntPapiExpansion untPapiExpansion;

    private static final long LOG_INTERVAL_MS = 500L;
    private final AtomicLong lastErrorLog = new AtomicLong(0);

    public PAPIManager(@NotNull UnlimitedNameTags plugin) {
        this.plugin = plugin;
        this.checkPapiEnabled();
    }

    public void checkPapiEnabled() {
        if (papiEnabled) {
            return;
        }

        this.papiEnabled = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (papiEnabled) {
            try {
                this.untPapiExpansion = new UntPapiExpansion(plugin);
                this.untPapiExpansion.register();
            } catch (Throwable e) {
                logWarning("Failed to register PlaceholderAPI expansion: " + e.getMessage(), e);
            }
        } else {
            plugin.getLogger().info("PlaceholderAPI is not enabled, some features may not work.");
        }
    }

    @NotNull
    public String setPlaceholders(Player player, String text) {
        if (text.isEmpty() || !papiEnabled) {
            return text;
        }
        try {
            final String firstReplacement = PlaceholderAPI.setPlaceholders(player, text);
            return PlaceholderAPI.setPlaceholders(player, firstReplacement);
        } catch (Throwable e) {
            logError("Failed to set placeholders for text: " + text, e);
            return text;
        }
    }

    @NotNull
    public String setRelationalPlaceholders(@NotNull Player whoSees, @NotNull Player target, @NotNull String text) {
        if (text.isEmpty() || !papiEnabled) {
            return text;
        }
        try {
            final String firstReplacement = PlaceholderAPI.setRelationalPlaceholders(whoSees, target, text);
            return PlaceholderAPI.setRelationalPlaceholders(whoSees, target, firstReplacement);
        } catch (Throwable e) {
            logError("Failed to set relational placeholders for text: " + text, e);
            return text;
        }
    }

    public void close() {
        if (papiEnabled && untPapiExpansion != null) {
            untPapiExpansion.unregister();
        }
    }

    private void logError(String message, Throwable e) {
        long now = System.currentTimeMillis();
        long last = lastErrorLog.get();
        if (now - last > LOG_INTERVAL_MS) {
            if (lastErrorLog.compareAndSet(last, now)) {
                plugin.getLogger().log(Level.SEVERE, message, e);
            }
        }
    }

    private void logWarning(String message, Throwable e) {
        plugin.getLogger().warning(message);
    }
}
