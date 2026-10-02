package com.fivemcodehub.hypereco.service;

import com.fivemcodehub.hypereco.storage.RedisCache;
import com.fivemcodehub.hypereco.storage.SqlBackend;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authoritative in-memory ledger with a write-behind flush worker.
 *
 * <p>All money arithmetic is delegated to {@link Ledger}, which carries no
 * Bukkit dependency and is unit tested directly. This class owns only the
 * scheduling, persistence and cross-server concerns.
 */
public final class EconomyService {

    /** Currency is tracked in minor units; 100 units = 1.00 of display currency. */
    public static final long MINOR_UNITS_PER_WHOLE = Ledger.MINOR_UNITS_PER_WHOLE;

    private final JavaPlugin plugin;
    private final SqlBackend sql;
    private final RedisCache redis;

    private final Ledger ledger = new Ledger();
    private final Map<UUID, String> usernames = new ConcurrentHashMap<>();

    private final AtomicLong flushCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    private BukkitTask flushTask;
    private long startingBalance;
    private boolean journalEnabled;

    public EconomyService(JavaPlugin plugin, SqlBackend sql, RedisCache redis) {
        this.plugin = plugin;
        this.sql = sql;
        this.redis = redis;
    }

    public void start() {
        var cfg = plugin.getConfig();
        this.startingBalance = Math.round(
                cfg.getDouble("economy.starting-balance", 100.0) * MINOR_UNITS_PER_WHOLE);
        this.journalEnabled = cfg.getBoolean("economy.journal-transactions", true);

        long interval = Math.max(20L, cfg.getLong("storage.flush-interval-ticks", 600L));
        this.flushTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::flushNow, interval, interval);

        if (redis != null) {
            // Remote node changed a balance: adopt it only for players not online
            // here, otherwise the local value is the fresher one.
            redis.subscribe((uuid, units) -> {
                if (plugin.getServer().getPlayer(uuid) == null) ledger.seed(uuid, units);
            });
        }
    }

    /** Loads an account off the main thread. Safe to call from an async join handler. */
    public void loadAsync(UUID uuid, String username) {
        usernames.put(uuid, username);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long units = sql.loadBalance(uuid, username, startingBalance);
                if (!ledger.isLoaded(uuid)) ledger.seed(uuid, units);
            } catch (Exception ex) {
                plugin.getLogger().warning("Balance load failed for " + username
                        + ": " + ex.getMessage());
            }
        });
    }

    public long balanceMinor(UUID uuid) {
        return ledger.balanceOf(uuid);
    }

    public double balance(UUID uuid) {
        return ledger.displayBalanceOf(uuid);
    }

    public boolean isLoaded(UUID uuid) {
        return ledger.isLoaded(uuid);
    }

    /** Exposed for diagnostics and tests. */
    public Ledger ledger() {
        return ledger;
    }

    /**
     * Applies a signed delta atomically.
     *
     * @return true if applied; false if it would overdraw the account
     */
    public boolean compute(UUID uuid, long deltaMinor, String reason) {
        Ledger.Result result = ledger.apply(uuid, deltaMinor);

        if (result != Ledger.Result.APPLIED) {
            rejectedCount.incrementAndGet();
            if (result == Ledger.Result.OVERFLOW) {
                plugin.getLogger().warning("Rejected overflowing delta " + deltaMinor
                        + " for " + uuid);
            }
            return false;
        }

        if (redis != null) redis.publishInvalidation(uuid, ledger.balanceOf(uuid));

        if (journalEnabled) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    sql.appendJournal(uuid, deltaMinor, reason);
                } catch (Exception ex) {
                    plugin.getLogger().fine("Journal append failed: " + ex.getMessage());
                }
            });
        }
        return true;
    }

    /**
     * Moves funds between two accounts without an intermediate state in which
     * the money exists in neither account.
     */
    public boolean transfer(UUID from, UUID to, long amountMinor) {
        if (ledger.transfer(from, to, amountMinor) != Ledger.Result.APPLIED) {
            rejectedCount.incrementAndGet();
            return false;
        }

        if (redis != null) {
            redis.publishInvalidation(from, ledger.balanceOf(from));
            redis.publishInvalidation(to, ledger.balanceOf(to));
        }
        return true;
    }

    public void setBalance(UUID uuid, long minorUnits) {
        ledger.setBalance(uuid, minorUnits);
    }

    /** Drains the dirty set into SQL. Must not run on the main thread. */
    public void flushNow() {
        Map<UUID, Long> snapshot = ledger.drainDirty();
        if (snapshot.isEmpty()) return;

        try {
            int written = sql.flush(snapshot, usernames);
            flushCount.addAndGet(written);
        } catch (Exception ex) {
            // Re-queue so the next pass retries instead of dropping the writes.
            ledger.requeue(snapshot.keySet());
            plugin.getLogger().warning("Flush failed, re-queued "
                    + snapshot.size() + " account(s): " + ex.getMessage());
        }
    }

    public void unload(UUID uuid) {
        ledger.markDirty(uuid);
        // Balance stays cached until the next flush so a rejoin is a cache hit.
    }

    public void shutdown() {
        if (flushTask != null) flushTask.cancel();
        flushNow();
        plugin.getLogger().info("Economy flushed: " + flushCount.get()
                + " write(s), " + rejectedCount.get() + " rejected transaction(s)");
    }

    public long totalFlushes() {
        return flushCount.get();
    }

    public int cachedAccounts() {
        return ledger.size();
    }
}
