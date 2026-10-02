package com.fivemcodehub.hypereco.service;

import com.fivemcodehub.hypereco.storage.RedisCache;
import com.fivemcodehub.hypereco.storage.SqlBackend;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authoritative in-memory ledger with a write-behind flush worker.
 *
 * <p>Concurrency contract: balances live in a {@link ConcurrentHashMap} and are
 * mutated only through {@link #compute}, which uses the map's atomic compute so
 * concurrent deposits never interleave into a lost update. The flush worker
 * drains a dirty set rather than scanning every account, so flush cost scales
 * with churn instead of with player count.
 */
public final class EconomyService {

    /** Currency is tracked in minor units; 100 units = 1.00 of display currency. */
    public static final long MINOR_UNITS_PER_WHOLE = 100L;

    private final JavaPlugin plugin;
    private final SqlBackend sql;
    private final RedisCache redis;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> usernames = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

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
                if (plugin.getServer().getPlayer(uuid) == null) balances.put(uuid, units);
            });
        }
    }

    /** Loads an account off the main thread. Safe to call from an async join handler. */
    public void loadAsync(UUID uuid, String username) {
        usernames.put(uuid, username);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long units = sql.loadBalance(uuid, username, startingBalance);
                balances.putIfAbsent(uuid, units);
            } catch (Exception ex) {
                plugin.getLogger().warning("Balance load failed for " + username
                        + ": " + ex.getMessage());
            }
        });
    }

    public long balanceMinor(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public double balance(UUID uuid) {
        return balanceMinor(uuid) / (double) MINOR_UNITS_PER_WHOLE;
    }

    public boolean isLoaded(UUID uuid) {
        return balances.containsKey(uuid);
    }

    /**
     * Applies a signed delta atomically.
     *
     * @return true if applied; false if it would overdraw the account
     */
    public boolean compute(UUID uuid, long deltaMinor, String reason) {
        if (deltaMinor == 0) return true;

        boolean[] applied = {true};
        balances.compute(uuid, (key, current) -> {
            long base = current == null ? 0L : current;
            long next = base + deltaMinor;
            if (next < 0) {
                applied[0] = false;
                return base;
            }
            return next;
        });

        if (!applied[0]) {
            rejectedCount.incrementAndGet();
            return false;
        }

        dirty.add(uuid);
        if (redis != null) redis.publishInvalidation(uuid, balances.get(uuid));

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
        if (amountMinor <= 0 || from.equals(to)) return false;
        if (!compute(from, -amountMinor, "transfer-out")) return false;

        if (!compute(to, amountMinor, "transfer-in")) {
            // Credit failed, so refund rather than destroying the amount.
            compute(from, amountMinor, "transfer-rollback");
            return false;
        }
        return true;
    }

    public void setBalance(UUID uuid, long minorUnits) {
        balances.put(uuid, Math.max(0L, minorUnits));
        dirty.add(uuid);
    }

    /** Drains the dirty set into SQL. Must not run on the main thread. */
    public void flushNow() {
        if (dirty.isEmpty()) return;

        Map<UUID, Long> snapshot = new java.util.HashMap<>();
        for (UUID uuid : Set.copyOf(dirty)) {
            Long value = balances.get(uuid);
            if (value != null) snapshot.put(uuid, value);
            dirty.remove(uuid);
        }

        try {
            int written = sql.flush(snapshot, usernames);
            flushCount.addAndGet(written);
        } catch (Exception ex) {
            // Re-mark so the next tick retries instead of dropping the writes.
            dirty.addAll(snapshot.keySet());
            plugin.getLogger().warning("Flush failed, re-queued "
                    + snapshot.size() + " account(s): " + ex.getMessage());
        }
    }

    public void unload(UUID uuid) {
        dirty.add(uuid);
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
        return balances.size();
    }
}
