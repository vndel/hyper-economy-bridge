package com.fivemcodehub.hypereco.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the money arithmetic.
 *
 * <p>These exist because a defect here silently creates or destroys currency,
 * and that class of bug is not reliably caught by reading the code.
 */
class LedgerTest {

    private final Ledger ledger = new Ledger();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    @DisplayName("a deposit increases the balance by exactly the delta")
    void depositIsExact() {
        ledger.seed(alice, 10_000L);
        assertEquals(Ledger.Result.APPLIED, ledger.apply(alice, 2_550L));
        assertEquals(12_550L, ledger.balanceOf(alice));
        assertEquals(125.50, ledger.displayBalanceOf(alice), 0.0001);
    }

    @Test
    @DisplayName("a withdrawal beyond the balance is rejected and changes nothing")
    void overdraftRejected() {
        ledger.seed(alice, 500L);
        assertEquals(Ledger.Result.INSUFFICIENT_FUNDS, ledger.apply(alice, -501L));
        assertEquals(500L, ledger.balanceOf(alice), "balance must be untouched");
    }

    @Test
    @DisplayName("spending the exact balance is allowed and lands on zero")
    void exactSpendAllowed() {
        ledger.seed(alice, 500L);
        assertEquals(Ledger.Result.APPLIED, ledger.apply(alice, -500L));
        assertEquals(0L, ledger.balanceOf(alice));
    }

    @Test
    @DisplayName("a delta that would overflow a long is rejected, not wrapped")
    void overflowRejected() {
        // A wrapped long turns a huge deposit into a negative balance, which is
        // the classic free-money exploit.
        ledger.seed(alice, Long.MAX_VALUE - 10L);
        assertEquals(Ledger.Result.OVERFLOW, ledger.apply(alice, 100L));
        assertEquals(Long.MAX_VALUE - 10L, ledger.balanceOf(alice));
        assertTrue(ledger.balanceOf(alice) > 0, "balance must never go negative");
    }

    @Test
    @DisplayName("a transfer conserves total supply")
    void transferConservesSupply() {
        ledger.seed(alice, 10_000L);
        ledger.seed(bob, 3_000L);
        long before = ledger.totalSupply();

        assertEquals(Ledger.Result.APPLIED, ledger.transfer(alice, bob, 2_500L));

        assertEquals(7_500L, ledger.balanceOf(alice));
        assertEquals(5_500L, ledger.balanceOf(bob));
        assertEquals(before, ledger.totalSupply(), "currency must not be created or destroyed");
    }

    @Test
    @DisplayName("a failed transfer leaves both balances untouched")
    void failedTransferIsAtomic() {
        ledger.seed(alice, 100L);
        ledger.seed(bob, 50L);
        long before = ledger.totalSupply();

        assertEquals(Ledger.Result.INSUFFICIENT_FUNDS, ledger.transfer(alice, bob, 101L));

        assertEquals(100L, ledger.balanceOf(alice));
        assertEquals(50L, ledger.balanceOf(bob));
        assertEquals(before, ledger.totalSupply());
    }

    @Test
    @DisplayName("a transfer that would overflow the recipient refunds the sender")
    void overflowingTransferRefunds() {
        ledger.seed(alice, 1_000L);
        ledger.seed(bob, Long.MAX_VALUE - 10L);
        long before = ledger.totalSupply();

        assertEquals(Ledger.Result.OVERFLOW, ledger.transfer(alice, bob, 1_000L));

        assertEquals(1_000L, ledger.balanceOf(alice), "sender must be refunded");
        assertEquals(before, ledger.totalSupply());
    }

    @Test
    @DisplayName("self-transfer and non-positive amounts are refused")
    void degenerateTransfersRefused() {
        ledger.seed(alice, 1_000L);
        assertEquals(Ledger.Result.INSUFFICIENT_FUNDS, ledger.transfer(alice, alice, 100L));
        assertEquals(Ledger.Result.INSUFFICIENT_FUNDS, ledger.transfer(alice, bob, 0L));
        assertEquals(Ledger.Result.INSUFFICIENT_FUNDS, ledger.transfer(alice, bob, -100L));
        assertEquals(1_000L, ledger.balanceOf(alice));
    }

    @Test
    @DisplayName("concurrent deposits do not lose updates")
    void concurrentDepositsAreAtomic() throws InterruptedException {
        // The real reason the ledger uses map.compute(): a read-modify-write
        // would drop updates under contention and the loss would be silent.
        ledger.seed(alice, 0L);

        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) ledger.apply(alice, 10L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "workers did not finish");
        pool.shutdownNow();

        assertEquals(threads * perThread * 10L, ledger.balanceOf(alice),
                "every concurrent deposit must be reflected exactly once");
    }

    @Test
    @DisplayName("concurrent withdrawals never drive the balance negative")
    void concurrentWithdrawalsNeverOverdraw() throws InterruptedException {
        ledger.seed(alice, 1_000L);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 16 threads x 200 attempts of 10 units far exceeds 1000.
                    for (int i = 0; i < 200; i++) ledger.apply(alice, -10L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(0L, ledger.balanceOf(alice), "should settle at exactly zero");
        assertTrue(ledger.balanceOf(alice) >= 0, "must never go negative");
    }

    @Test
    @DisplayName("draining dirty writes clears them and a failed flush re-queues")
    void dirtyTrackingRoundTrips() {
        ledger.seed(alice, 100L);
        ledger.apply(alice, 50L);
        ledger.apply(bob, 25L);

        assertEquals(2, ledger.pendingWrites());

        var drained = ledger.drainDirty();
        assertEquals(2, drained.size());
        assertEquals(0, ledger.pendingWrites(), "drain must clear the set");
        assertEquals(150L, drained.get(alice));

        // Simulates a flush failure path.
        ledger.requeue(drained.keySet());
        assertEquals(2, ledger.pendingWrites(), "failed writes must be retried");
    }

    @Test
    @DisplayName("a rejected mutation does not mark the account dirty")
    void rejectedMutationIsNotPersisted() {
        ledger.seed(alice, 100L);
        ledger.drainDirty();

        ledger.apply(alice, -500L);
        assertEquals(0, ledger.pendingWrites(), "a no-op must not schedule a write");
    }

    @Test
    @DisplayName("a zero delta is a no-op")
    void zeroDeltaIsNoOp() {
        ledger.seed(alice, 100L);
        ledger.drainDirty();
        assertEquals(Ledger.Result.APPLIED, ledger.apply(alice, 0L));
        assertEquals(100L, ledger.balanceOf(alice));
        assertEquals(0, ledger.pendingWrites());
    }

    @Test
    @DisplayName("setBalance clamps negatives to zero")
    void setBalanceClamps() {
        ledger.setBalance(alice, -5_000L);
        assertEquals(0L, ledger.balanceOf(alice));
    }

    @Test
    @DisplayName("an unknown account reads as zero rather than throwing")
    void unknownAccountReadsZero() {
        assertEquals(0L, ledger.balanceOf(UUID.randomUUID()));
        assertFalse(ledger.isLoaded(UUID.randomUUID()));
    }
}
