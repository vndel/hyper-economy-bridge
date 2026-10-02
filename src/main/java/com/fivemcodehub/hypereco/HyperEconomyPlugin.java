package com.fivemcodehub.hypereco;

import com.fivemcodehub.hypereco.command.BalanceCommand;
import com.fivemcodehub.hypereco.command.EcoAdminCommand;
import com.fivemcodehub.hypereco.command.PayCommand;
import com.fivemcodehub.hypereco.listener.PlayerSessionListener;
import com.fivemcodehub.hypereco.service.EconomyService;
import com.fivemcodehub.hypereco.storage.RedisCache;
import com.fivemcodehub.hypereco.storage.SqlBackend;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for HyperEconomyBridge.
 *
 * <p>Design notes: no balance read ever touches the main thread. Reads resolve
 * from an in-process cache populated on join, writes are queued and flushed by
 * a write-behind worker. The SQL backend is the source of truth; Redis only
 * carries cross-server invalidation so a balance changed on one node is not
 * served stale by another.
 */
public final class HyperEconomyPlugin extends JavaPlugin {

    private EconomyService economy;
    private SqlBackend sql;
    private RedisCache redis;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            this.sql = new SqlBackend(this);
            this.sql.initialise();
        } catch (Exception ex) {
            getLogger().severe("Storage initialisation failed: " + ex.getMessage());
            getLogger().severe("Disabling to avoid serving inconsistent balances.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Redis is optional: a single-node deployment does not need it.
        if (getConfig().getBoolean("redis.enabled", false)) {
            this.redis = new RedisCache(this);
            this.redis.connect();
        }

        this.economy = new EconomyService(this, sql, redis);
        this.economy.start();

        getServer().getPluginManager().registerEvents(new PlayerSessionListener(economy), this);

        registerCommand("balance", new BalanceCommand(economy));
        registerCommand("pay", new PayCommand(this, economy));
        registerCommand("ecoadmin", new EcoAdminCommand(this, economy));

        getLogger().info("HyperEconomyBridge enabled (backend=" + sql.describe()
                + ", redis=" + (redis != null) + ")");
    }

    @Override
    public void onDisable() {
        // Flush synchronously here: the scheduler is already shutting down, and
        // losing the queue would mean losing player money.
        if (economy != null) economy.shutdown();
        if (redis != null) redis.close();
        if (sql != null) sql.close();
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        var cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' missing from plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
    }

    public EconomyService economy() {
        return economy;
    }
}
