package com.fivemcodehub.hypereco.command;

import com.fivemcodehub.hypereco.service.EconomyService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

public final class EcoAdminCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final EconomyService economy;

    public EcoAdminCommand(JavaPlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(MM.deserialize(
                    "<gray>/ecoadmin <give|take|set|reload|stats> [player] [amount]</gray>"));
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        if (action.equals("reload")) {
            plugin.reloadConfig();
            sender.sendMessage(MM.deserialize("<green>Configuration reloaded.</green>"));
            return true;
        }

        if (action.equals("stats")) {
            sender.sendMessage(MM.deserialize(
                    "<gray>Cached accounts:</gray> <white><n></white> <gray>| writes:</gray> <white><w></white>",
                    Placeholder.unparsed("n", String.valueOf(economy.cachedAccounts())),
                    Placeholder.unparsed("w", String.valueOf(economy.totalFlushes()))));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(MM.deserialize("<red>Usage: /ecoadmin " + action
                    + " <player> <amount></red>"));
            return true;
        }

        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MM.deserialize("<red>That player is not online.</red>"));
            return true;
        }

        long minor;
        try {
            minor = new BigDecimal(args[2]).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            sender.sendMessage(MM.deserialize("<red>Invalid amount.</red>"));
            return true;
        }

        boolean ok = switch (action) {
            case "give" -> economy.compute(target.getUniqueId(), minor, "admin-give");
            case "take" -> economy.compute(target.getUniqueId(), -minor, "admin-take");
            case "set"  -> {
                economy.setBalance(target.getUniqueId(), minor);
                yield true;
            }
            default -> {
                sender.sendMessage(MM.deserialize("<red>Unknown action.</red>"));
                yield false;
            }
        };

        if (ok) {
            sender.sendMessage(MM.deserialize("<green>Done. <name> now holds <amt>.</green>",
                    Placeholder.unparsed("name", target.getName()),
                    Placeholder.unparsed("amt",
                            String.format("%,.2f", economy.balance(target.getUniqueId())))));
        } else if (action.equals("take")) {
            sender.sendMessage(MM.deserialize("<red>That would overdraw the account.</red>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("give", "take", "set", "reload", "stats");
        if (args.length == 2) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
