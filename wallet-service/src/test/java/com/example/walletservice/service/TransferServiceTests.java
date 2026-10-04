package com.example.walletservice.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.walletservice.domain.EntryType;
import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletEntry;
import com.example.walletservice.domain.WalletStatus;
import com.example.walletservice.service.TransferService.TransferResult;
import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transfer invariants, against a real Postgres.
 *
 * <p>Every one of these needs a genuine database. The conditional {@code UPDATE}, the
 * partial unique index, {@code CHECK (balance >= 0)} and the row locks that make two
 * concurrent withdrawals safe are all behaviours of the database engine -- none of
 * them can be reproduced with mocks, and a mock that "passes" here would prove only
 * that the mock was called.
 */
class TransferServiceTests extends PostgresIntegrationTest {

    @Autowired
    private TransferService transfers;

    private UUID customerFor(String name) {
        UUID ownerId = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), ownerId, name);
        return ownerId;
    }

    /** Seeds a wallet with an opening balance, bypassing transfers entirely. */
    private Wallet fund(UUID ownerId, String currency, String opening) {
        UUID customerId = customers.findByOwnerId(ownerId).orElseThrow().getId();
        Wallet wallet = new Wallet(UUID.randomUUID(), customerId, currency, new BigDecimal(opening));
        return wallets.saveAndFlush(wallet);
    }

    @Test
    void movesMoneyAndRecordsBothLegs() {
        UUID owner = customerFor("Alice");
        Wallet from = fund(owner, "USD", "100.00");
        Wallet to = fund(customerFor("Payee-1"), "USD", "10.00");

        TransferResult result = transfers.transfer(owner,
                Transfer.requested(from.getId(), to.getId(), new BigDecimal("25.00"), "key-1"),
                UUID.randomUUID());

        assertThat(result.replayed()).isFalse();
        assertThat(wallets.findById(from.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("75.00");
        assertThat(wallets.findById(to.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("35.00");

        List<WalletEntry> ledger = entries.findAll();
        assertThat(ledger).hasSize(2);

        WalletEntry out = ledger.stream()
                .filter(e -> e.getEntryType() == EntryType.TRANSFER_OUT).findFirst().orElseThrow();
        WalletEntry in = ledger.stream()
                .filter(e -> e.getEntryType() == EntryType.TRANSFER_IN).findFirst().orElseThrow();

        // balance_after is the whole point of the ledger: it lets a reader verify a
        // balance at any past point without replaying every entry.
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("75.00");
        assertThat(in.getBalanceAfter()).isEqualByComparingTo("35.00");
        assertThat(out.getTransferId()).isEqualTo(result.transferId());
        assertThat(in.getTransferId()).isEqualTo(result.transferId());
    }

    @Test
    void announcesTheTransferThroughTheOutbox() {
        UUID owner = customerFor("Bob");
        Wallet from = fund(owner, "USD", "100.00");
        Wallet to = fund(customerFor("Payee-2"), "USD", "0.00");

        transfers.transfer(owner,
                Transfer.requested(from.getId(), to.getId(), new BigDecimal("40.00"), "key-out"),
                UUID.randomUUID());

        // The event must be committed with the money, never after it. A row that is
        // missing here means the two writes are not in one transaction, which is the
        // dual-write bug the outbox exists to prevent.
        assertThat(outbox.findAll()).hasSize(1);
        var event = outbox.findAll().get(0);
        assertThat(event.getEventType()).isEqualTo("wallet.transfer.completed");
        // Keyed by aggregate id so one transfer's events share a partition.
        assertThat(event.getAggregateId()).isNotNull();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).contains("40.00");
    }

    @Test
    void replayingTheSameKeyDoesNotMoveMoneyTwice() {
        UUID owner = customerFor("Carol");
        Wallet from = fund(owner, "USD", "100.00");
        Wallet to = fund(customerFor("Payee-3"), "USD", "0.00");

        Transfer first = Transfer.requested(from.getId(), to.getId(), new BigDecimal("30.00"), "same-key");
        TransferResult original = transfers.transfer(owner, first, UUID.randomUUID());
        TransferResult replay = transfers.transfer(owner, first, UUID.randomUUID());

        assertThat(replay.replayed()).isTrue();
        // The same transfer id, so the caller can correlate the retry with the
        // original rather than being handed a second, meaningless id.
        assertThat(replay.transferId()).isEqualTo(original.transferId());
        assertThat(wallets.findById(from.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("70.00");
        assertThat(entries.findAll()).hasSize(2);
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void rejectsASecondChargeWithTheSameKeyEvenWhenTheBalanceAllowsIt() {
        UUID owner = customerFor("Dave");
        Wallet from = fund(owner, "USD", "100.00");
        Wallet to = fund(customerFor("Payee-4"), "USD", "0.00");

        transfers.transfer(owner, Transfer.requested(from.getId(), to.getId(),
                new BigDecimal("10.00"), "dup"), UUID.randomUUID());
        TransferResult replay = transfers.transfer(owner, Transfer.requested(from.getId(), to.getId(),
                new BigDecimal("10.00"), "dup"), UUID.randomUUID());

        // The point of the guard: the balance could easily have covered a second 10.
        // It is the idempotency key, not the money, that makes this a replay.
        assertThat(replay.replayed()).isTrue();
        assertThat(wallets.findById(from.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("90.00");
    }

    @Test
    void refusesToOverdrawAndWritesNothing() {
        UUID owner = customerFor("Erin");
        Wallet from = fund(owner, "USD", "50.00");
        Wallet to = fund(customerFor("Payee-5"), "USD", "0.00");

        assertThatThrownBy(() -> transfers.transfer(owner,
                Transfer.requested(from.getId(), to.getId(), new BigDecimal("50.01"), "over"),
                UUID.randomUUID()))
                .isInstanceOf(TransferRejected.class)
                .extracting(e -> ((TransferRejected) e).reason())
                .isEqualTo(TransferRejected.Reason.INSUFFICIENT_FUNDS);

        // Nothing at all may be written on a rejected transfer. A partial debit, or a
        // debit with no matching entry, is unrecoverable without manual repair.
        assertThat(entries.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
        assertThat(wallets.findById(from.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("50.00");
    }

    @Test
    void refusesToConvertBetweenCurrencies() {
        UUID owner = customerFor("Frank");
        Wallet usd = fund(owner, "USD", "100.00");
        Wallet eur = fund(customerFor("Payee-6"), "EUR", "100.00");

        assertThatThrownBy(() -> transfers.transfer(owner,
                Transfer.requested(usd.getId(), eur.getId(), new BigDecimal("10.00"), "fx"),
                UUID.randomUUID()))
                .isInstanceOf(TransferRejected.class)
                .extracting(e -> ((TransferRejected) e).reason())
                .isEqualTo(TransferRejected.Reason.CURRENCY_MISMATCH);

        assertThat(entries.findAll()).isEmpty();
    }

    /** The normal case: money goes to somebody else's wallet. */
    @Test
    void paysAnotherCustomersWallet() {
        UUID payer = customerFor("Grace");
        UUID payee = customerFor("Hank");
        Wallet mine = fund(payer, "USD", "100.00");
        Wallet theirs = fund(payee, "USD", "5.00");

        TransferResult result = transfers.transfer(payer,
                Transfer.requested(mine.getId(), theirs.getId(), new BigDecimal("40.00"), "pay"),
                UUID.randomUUID());

        assertThat(result.replayed()).isFalse();
        assertThat(wallets.findById(mine.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("60.00");
        assertThat(wallets.findById(theirs.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("45.00");
        assertThat(entries.findAll()).hasSize(2);
    }

    /**
     * The payee's wallet may be credited by anyone, but nobody else's may be debited.
     *
     * <p>This is the asymmetry that makes the endpoint a payment rather than a
     * transfer between the caller's own accounts. It is also the security boundary:
     * the destination check is existence-only, so if ownership were ever applied to
     * both legs this test would be the one to change.
     */
    @Test
    void willNotDebitSomebodyElsesWallet() {
        UUID payer = customerFor("Lena");
        UUID victim = customerFor("Mallory");
        Wallet theirs = fund(victim, "USD", "100.00");
        Wallet mine = fund(payer, "USD", "100.00");

        // Reported as UNKNOWN_WALLET rather than FORBIDDEN: "not yours" would confirm
        // the wallet exists, which is a leak and lets ids be probed.
        assertThatThrownBy(() -> transfers.transfer(payer,
                Transfer.requested(theirs.getId(), mine.getId(), new BigDecimal("100.00"), "steal"),
                UUID.randomUUID()))
                .isInstanceOf(TransferRejected.class)
                .extracting(e -> ((TransferRejected) e).reason())
                .isEqualTo(TransferRejected.Reason.UNKNOWN_WALLET);

        assertThat(wallets.findById(theirs.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(entries.findAll()).isEmpty();
    }

    @Test
    void refusesAFrozenWallet() {
        UUID owner = customerFor("Ivy");
        Wallet from = fund(owner, "USD", "100.00");
        Wallet to = fund(customerFor("Payee-7"), "USD", "0.00");
        from.setStatus(WalletStatus.FROZEN);
        wallets.saveAndFlush(from);

        assertThatThrownBy(() -> transfers.transfer(owner,
                Transfer.requested(from.getId(), to.getId(), new BigDecimal("10.00"), "frozen"),
                UUID.randomUUID()))
                .isInstanceOf(TransferRejected.class)
                .extracting(e -> ((TransferRejected) e).reason())
                .isEqualTo(TransferRejected.Reason.WALLET_NOT_ACTIVE);
    }

    /**
     * Two withdrawals that together exceed the balance. Only one may succeed.
     *
     * <p>The interesting property is not that one throws, but that the surviving
     * balance is 20 and not -60. A read-check-write in Java would let both see 100,
     * both conclude they can afford 80, and both write. The conditional UPDATE and
     * Postgres's row lock are what prevent it, and nothing but a real concurrent
     * execution against a real engine can show that.
     */
    @Test
    void concurrentWithdrawalsCannotBothSucceed() throws Exception {
        UUID owner = customerFor("Jack");
        Wallet from = fund(owner, "USD", "100.00");
        Wallet to = fund(customerFor("Payee-8"), "USD", "0.00");

        // Distinct keys on purpose: this is a test of the balance check, not of the
        // idempotency guard. Same-key concurrency is covered separately.
        List<Boolean> outcomes = runTogether(
                () -> attempt(owner, from, to, "80.00", "race-a"),
                () -> attempt(owner, from, to, "80.00", "race-b"));

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(wallets.findById(from.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("20.00");
        // Two rows, not one: the survivor wrote both legs of its transfer. The loser's
        // debit was rolled back, so its entry went with it -- which is the whole claim,
        // since a rollback that left the entry behind would be a ledger that shows
        // money leaving a wallet whose balance never moved.
        assertThat(entries.findAll()).hasSize(2);
        assertThat(entries.findAll()).extracting(WalletEntry::getEntryType)
                .containsExactlyInAnyOrder(EntryType.TRANSFER_OUT, EntryType.TRANSFER_IN);
        assertThat(outbox.findAll()).hasSize(1);
    }

    /** The same key twice at once: exactly one transfer, charged once. */
    @Test
    void concurrentRetriesOfOneKeyChargeOnce() throws Exception {
        UUID owner = customerFor("Kate");
        Wallet from = fund(owner, "USD", "100.00");
        Wallet to = fund(customerFor("Payee-9"), "USD", "0.00");

        runTogether(
                () -> transfers.transfer(owner, Transfer.requested(from.getId(), to.getId(),
                        new BigDecimal("40.00"), "same-race"), UUID.randomUUID()) != null,
                () -> transfers.transfer(owner, Transfer.requested(from.getId(), to.getId(),
                        new BigDecimal("40.00"), "same-race"), UUID.randomUUID()) != null);

        assertThat(wallets.findById(from.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("60.00");
        assertThat(entries.findAll()).hasSize(2);
        assertThat(outbox.findAll()).hasSize(1);
    }

    /** Starts both tasks together, so they contend rather than running in sequence. */
    private List<Boolean> runTogether(Callable<Boolean> first, Callable<Boolean> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startTogether = new CountDownLatch(1);
            Future<Boolean> a = pool.submit(() -> {
                startTogether.await();
                return first.call();
            });
            Future<Boolean> b = pool.submit(() -> {
                startTogether.await();
                return second.call();
            });
            startTogether.countDown();
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /** @return true if the transfer went through */
    private boolean attempt(UUID owner, Wallet from, Wallet to, String amount, String key) {
        try {
            transfers.transfer(owner, Transfer.requested(from.getId(), to.getId(),
                    new BigDecimal(amount), key), UUID.randomUUID());
            return true;
        } catch (TransferRejected e) {
            return false;
        }
    }
}
