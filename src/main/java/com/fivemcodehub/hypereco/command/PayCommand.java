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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PayCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final EconomyService economy;

    /** Per-player cooldown guards against macro-driven transfer spam. */
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public PayCommand(JavaPlugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Players only.</red>"));
            return true;
        }
        if (args.length != 2) {
            player.sendMessage(MM.deserialize("<gray>Usage: /pay <player> <amount></gray>"));
            return true;
        }

        long cooldownMs = plugin.getConfig().getLong("economy.pay-cooldown-ms", 1_000L);
        long now = System.currentTimeMillis();
        Long previous = lastUse.get(player.getUniqueId());
        if (previous != null && now - previous < cooldownMs) {
            player.sendMessage(MM.deserialize("<red>Slow down.</red>"));
            return true;
        }

        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(MM.deserialize("<red>That player is not online.</red>"));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(MM.deserialize("<red>You cannot pay yourself.</red>"));
            return true;
        }

        long minor;
        try {
            // BigDecimal, not Double.parseDouble: "0.1" style input must not
            // drift, and scale > 2 has to be rejected rather than rounded.
            BigDecimal parsed = new BigDecimal(args[1]);
            if (parsed.scale() > 2) {
                player.sendMessage(MM.deserialize("<red>At most two decimal places.</red>"));
                return true;
            }
            minor = parsed.movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            player.sendMessage(MM.deserialize("<red>Invalid amount.</red>"));
            return true;
        }

        if (minor <= 0) {
            player.sendMessage(MM.deserialize("<red>Amount must be positive.</red>"));
            return true;
        }

        long maximum = Math.round(plugin.getConfig()
                .getDouble("economy.max-transfer", 1_000_000.0) * EconomyService.MINOR_UNITS_PER_WHOLE);
        if (minor > maximum) {
            player.sendMessage(MM.deserialize("<red>That exceeds the transfer limit.</red>"));
            return true;
        }

        if (!economy.transfer(player.getUniqueId(), target.getUniqueId(), minor)) {
            player.sendMessage(MM.deserialize("<red>Insufficient funds.</red>"));
            return true;
        }

        lastUse.put(player.getUniqueId(), now);
        String amount = String.format("%,.2f", minor / (double) EconomyService.MINOR_UNITS_PER_WHOLE);

        player.sendMessage(MM.deserialize("<green>Sent <gold><amt></gold> to <name>.</green>",
                Placeholder.unparsed("amt", amount),
                Placeholder.unparsed("name", target.getName())));
        target.sendMessage(MM.deserialize("<green>Received <gold><amt></gold> from <name>.</green>",
                Placeholder.unparsed("amt", amount),
                Placeholder.unparsed("name", player.getName())));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(20)
                    .toList();
        }
        return args.length == 2 ? List.of("100", "1000", "10000") : List.of();
    }
}
