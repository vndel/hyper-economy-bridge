package com.fivemcodehub.hypereco.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure in-memory balance ledger.
 *
 * <p>Deliberately free of any Bukkit dependency. All money arithmetic lives
 * here so it can be unit tested directly: a rounding or overdraw bug in this
 * class destroys player balances, and that is not something to verify by
 * inspection alone.
 *
 * <p>Balances are integer minor units (100 = 1.00 of display currency). A
 * {@code double} would leak or duplicate currency through repeated rounding.
 *
 * <p>Thread safety: mutations go through {@link ConcurrentHashMap#compute},
 * which applies the remapping function atomically per key. Two concurrent
 * deposits therefore cannot interleave into a lost update.
 */
public final class Ledger {

    /** 100 minor units = 1.00 of display currency. */
    public static final long MINOR_UNITS_PER_WHOLE = 100L;

    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    /** Outcome of an attempted mutation. */
    public enum Result {
        APPLIED,
        /** Rejected: the delta would have driven the balance negative. */
        INSUFFICIENT_FUNDS,
        /** Rejected: the delta would have overflowed a long. */
        OVERFLOW
    }

    public void seed(UUID uuid, long minorUnits) {
        balances.put(uuid, Math.max(0L, minorUnits));
    }

    public boolean isLoaded(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public long balanceOf(UUID uuid) {
        return balances.getOrDefault(uuid, 0L);
    }

    public double displayBalanceOf(UUID uuid) {
        return balanceOf(uuid) / (double) MINOR_UNITS_PER_WHOLE;
    }

    /**
     * Applies a signed delta atomically.
     *
     * <p>Overflow is checked rather than allowed to wrap: a wrapped long turns
     * a very large deposit into a negative balance, which is the classic way an
     * economy gets a free-money exploit.
     */
    public Result apply(UUID uuid, long deltaMinor) {
        if (deltaMinor == 0L) return Result.APPLIED;

        Result[] outcome = {Result.APPLIED};

        balances.compute(uuid, (key, current) -> {
            long base = current == null ? 0L : current;

            long next;
            try {
                next = Math.addExact(base, deltaMinor);
            } catch (ArithmeticException overflow) {
                outcome[0] = Result.OVERFLOW;
                return base;
            }

            if (next < 0L) {
                outcome[0] = Result.INSUFFICIENT_FUNDS;
                return base;
            }
            return next;
        });

        if (outcome[0] == Result.APPLIED) dirty.add(uuid);
        return outcome[0];
    }

    /**
     * Moves funds between two accounts.
     *
     * <p>Debits first, then credits, and refunds the debit if the credit fails.
     * There is no observable state in which the amount exists in neither
     * account.
     */
    public Result transfer(UUID from, UUID to, long amountMinor) {
        if (amountMinor <= 0L || from.equals(to)) return Result.INSUFFICIENT_FUNDS;

        Result debit = apply(from, -amountMinor);
        if (debit != Result.APPLIED) return debit;

        Result credit = apply(to, amountMinor);
        if (credit != Result.APPLIED) {
            // Refund rather than destroying the amount.
            apply(from, amountMinor);
            return credit;
        }
        return Result.APPLIED;
    }

    public void setBalance(UUID uuid, long minorUnits) {
        balances.put(uuid, Math.max(0L, minorUnits));
        dirty.add(uuid);
    }

    /** Removes and returns the pending writes. */
    public Map<UUID, Long> drainDirty() {
        Map<UUID, Long> snapshot = new java.util.HashMap<>();
        for (UUID uuid : Set.copyOf(dirty)) {
            Long value = balances.get(uuid);
            if (value != null) snapshot.put(uuid, value);
            dirty.remove(uuid);
        }
        return snapshot;
    }

    /** Re-queues writes after a failed flush so they are retried, not lost. */
    public void requeue(Set<UUID> uuids) {
        dirty.addAll(uuids);
    }

    public void markDirty(UUID uuid) {
        dirty.add(uuid);
    }

    public int pendingWrites() {
        return dirty.size();
    }

    public int size() {
        return balances.size();
    }

    /** Total currency in circulation; used to assert conservation in tests. */
    public long totalSupply() {
        return balances.values().stream().mapToLong(Long::longValue).sum();
    }
}
