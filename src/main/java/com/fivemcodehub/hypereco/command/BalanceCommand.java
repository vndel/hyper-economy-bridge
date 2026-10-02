package com.fivemcodehub.hypereco.command;

import com.fivemcodehub.hypereco.service.EconomyService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BalanceCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final EconomyService economy;

    public BalanceCommand(EconomyService economy) {
        this.economy = economy;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(MM.deserialize("<red>Console must specify a player.</red>"));
                return true;
            }
            sender.sendMessage(MM.deserialize(
                    "<gray>Balance:</gray> <gold><amount></gold>",
                    Placeholder.unparsed("amount", format(economy.balance(player.getUniqueId())))));
            return true;
        }

        if (!sender.hasPermission("hypereco.balance.others")) {
            sender.sendMessage(MM.deserialize("<red>You cannot view other balances.</red>"));
            return true;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!economy.isLoaded(target.getUniqueId())) {
            sender.sendMessage(MM.deserialize(
                    "<yellow>No cached account for that player.</yellow>"));
            return true;
        }

        sender.sendMessage(MM.deserialize(
                "<gray><name>:</gray> <gold><amount></gold>",
                Placeholder.unparsed("name", args[0]),
                Placeholder.unparsed("amount", format(economy.balance(target.getUniqueId())))));
        return true;
    }

    private String format(double value) {
        return String.format("%,.2f", value);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1 || !sender.hasPermission("hypereco.balance.others")) return List.of();
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
                .limit(20)
                .toList();
    }
}
