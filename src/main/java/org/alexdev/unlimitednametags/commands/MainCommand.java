package org.alexdev.unlimitednametags.commands;

import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.config.Formatter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Permission;

@SuppressWarnings("unused")
public final class MainCommand {

    private final UnlimitedNameTags plugin;

    public MainCommand(final UnlimitedNameTags plugin) {
        this.plugin = plugin;
    }

    @Command("unt|unlimitednametags")
    @CommandDescription("Main command for UnlimitedNameTags")
    public void onMain(final CommandSourceStack senderStack) {
        final CommandSender sender = senderStack.getSender();
        plugin.getKyoriManager().sendMessage(sender,
                Formatter.LEGACY.format(plugin, sender, "&aUnlimitedNameTags v" + plugin.getDescription().getVersion()));
        plugin.getKyoriManager()
                .sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&a/unt reload &7- Reloads the plugin"));
        plugin.getKyoriManager()
                .sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&a/unt debug &7- Debugs the plugin"));
        plugin.getKyoriManager()
                .sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&a/unt hide <player> &7- Hides the nametag"));
        plugin.getKyoriManager()
                .sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&a/unt show <player> &7- Shows the nametag"));
        plugin.getKyoriManager().sendMessage(sender,
                Formatter.LEGACY.format(plugin, sender, "&a/unt debugger <true/false> &7- Enable or disable the debugger"));
    }

    @Command("unt reload")
    @CommandDescription("Reloads the plugin")
    @Permission("unt.reload")
    public void onReload(final CommandSourceStack senderStack) {
        final CommandSender sender = senderStack.getSender();
        plugin.getConfigManager().reload();
        plugin.getNametagManager().reload();
        plugin.getPlaceholderManager().reload();
        plugin.getKyoriManager()
                .sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&aUnlimitedNameTags has been reloaded!"));
    }

    @Command("unt debugger <debug>")
    @CommandDescription("Enables/Disables the debug mode")
    @Permission("unt.debug")
    public void onDebug(final CommandSourceStack senderStack, @Argument("debug") final boolean debug) {
        final CommandSender sender = senderStack.getSender();
        plugin.getNametagManager().setDebug(debug);
        plugin.getKyoriManager()
                .sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&aUnlimitedNameTags debug mode set to " + debug));
    }

    @Command("unt debug")
    @CommandDescription("Debugs the plugin")
    @Permission("unt.debug")
    public void onDebug(final CommandSourceStack senderStack) {
        final CommandSender sender = (CommandSender) senderStack.getSender();
        plugin.getNametagManager().debug(sender);
    }

    @Command("unt hide <target>")
    @CommandDescription("Hides the nametag")
    @Permission("unt.hide")
    public void onHide(final CommandSourceStack senderStack, @Argument("target") final Player target) {
        plugin.getNametagManager().removeAllViewers(target);
    }

    @Command("unt show <target>")
    @CommandDescription("Shows the nametag")
    @Permission("unt.show")
    public void onShow(final CommandSourceStack senderStack, @Argument("target") final Player target) {
        plugin.getNametagManager().showToTrackedPlayers(target);
    }

    @Command("unt refresh <target>")
    @CommandDescription("Refreshes the nametag of a player for you")
    @Permission("unt.refresh")
    public void onRefresh(final CommandSourceStack senderStack, @Argument("target") final Player target) {
        final CommandSender sender = senderStack.getSender();
        if (!(sender instanceof Player player)) {
            plugin.getKyoriManager().sendMessage(sender,
                    Formatter.LEGACY.format(plugin, sender, "&cOnly players can use this command."));
            return;
        }
        plugin.getNametagManager().getPacketDisplayTexts(target)
                .forEach(packetDisplayText -> packetDisplayText.refreshForPlayer(player));
    }

    @Command("unt billboard <billboard>")
    @CommandDescription("Sets the default billboard")
    @Permission("unt.billboard")
    public void onBillboard(final CommandSourceStack senderStack,
            @Argument("billboard") final AbstractDisplayMeta.BillboardConstraints billboardConstraints) {
        final CommandSender sender = senderStack.getSender();
        plugin.getConfigManager().getSettings().setDefaultBillboard(billboardConstraints);
        plugin.getConfigManager().save();
        plugin.getNametagManager().reload();
        plugin.getKyoriManager().sendMessage(sender,
                Formatter.LEGACY.format(plugin, sender, "&aDefault billboard set to " + billboardConstraints.name()));
    }

    @Command("unt formatter <formatter>")
    @CommandDescription("Sets the default formatter")
    @Permission("unt.formatter")
    public void onFormatter(final CommandSourceStack senderStack, @Argument("formatter") final Formatter formatter) {
        final CommandSender sender = senderStack.getSender();
        plugin.getConfigManager().getSettings().setFormat(formatter);
        plugin.getConfigManager().save();
        plugin.getNametagManager().reload();
        plugin.getKyoriManager()
                .sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&aDefault formatter set to " + formatter.name()));
    }

    @Command("unt hideOtherNametags")
    @CommandDescription("Hides other nametags")
    @Permission("unt.hideOtherNametags")
    public void onHideOtherNametags(final CommandSourceStack senderStack) {
        onHideOtherNametags(senderStack, false);
    }

    @Command("unt hideOtherNametags <hideMessage>")
    @CommandDescription("Hides other nametags")
    @Permission("unt.hideOtherNametags")
    public void onHideOtherNametags(final CommandSourceStack senderStack, @Argument("hideMessage") final boolean hideMessage) {
        final CommandSender sender = senderStack.getSender();
        if (!(sender instanceof Player player)) {
            plugin.getKyoriManager().sendMessage(sender,
                    Formatter.LEGACY.format(plugin, sender, "&cOnly players can use this command."));
            return;
        }
        if (!plugin.getNametagManager().isHiddenOtherNametags(player)) {
            plugin.getNametagManager().hideOtherNametags(player);
            if (!hideMessage) {
                plugin.getKyoriManager().sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&aOther nametags hidden"));
            }
        }
    }

    @Command("unt showOtherNametags")
    @CommandDescription("Shows other nametags")
    @Permission("unt.showOtherNametags")
    public void onShowOtherNametags(final CommandSourceStack senderStack) {
        onShowOtherNametags(senderStack, false);
    }

    @Command("unt showOtherNametags <hideMessage>")
    @CommandDescription("Shows other nametags")
    @Permission("unt.showOtherNametags")
    public void onShowOtherNametags(final CommandSourceStack senderStack, @Argument("hideMessage") final boolean hideMessage) {
        final CommandSender sender = senderStack.getSender();
        if (!(sender instanceof Player player)) {
            plugin.getKyoriManager().sendMessage(sender,
                    Formatter.LEGACY.format(plugin, sender, "&cOnly players can use this command."));
            return;
        }
        if (plugin.getNametagManager().isHiddenOtherNametags(player)) {
            plugin.getNametagManager().showOtherNametags(player);
            if (!hideMessage) {
                plugin.getKyoriManager().sendMessage(sender, Formatter.LEGACY.format(plugin, sender, "&aOther nametags shown"));
            }
        }
    }
}
