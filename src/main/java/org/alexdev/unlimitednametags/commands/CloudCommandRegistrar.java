package org.alexdev.unlimitednametags.commands;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.meta.SimpleCommandMeta;
import org.incendo.cloud.paper.PaperCommandManager;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * Boots cloud commands and registers the plugin command handlers.
 */
public final class CloudCommandRegistrar {

    private final UnlimitedNameTags plugin;

    public CloudCommandRegistrar(@NotNull UnlimitedNameTags plugin) {
        this.plugin = plugin;
    }

    public void register() {
        final PaperCommandManager<CommandSourceStack> manager;
        try {
            manager = createManager();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize command manager (cloud).", t);
            return;
        }

        final AnnotationParser<CommandSourceStack> parser = new AnnotationParser<>(
                manager,
                CommandSourceStack.class,
                parameters -> SimpleCommandMeta.empty()
        );

        parser.parse(new MainCommand(plugin));
    }

    private PaperCommandManager<CommandSourceStack> createManager() {
        return PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.asyncCoordinator())
                .buildOnEnable(plugin);
    }
}
