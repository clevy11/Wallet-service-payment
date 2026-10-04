package com.example.walletservice.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletEntry;
import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opening wallets.
 *
 * <p>The theme is idempotency, and it is a different problem from the transfer's.
 * A transfer is a relative operation -- two legitimate transfers of 50 are
 * indistinguishable from one transfer sent twice -- so it needs the caller's key and a
 * unique index on a hashed reference. Opening a wallet is a state-setting operation:
 * "this customer has a USD wallet" is a fact with one right answer, so
 * {@code uq_wallets_customer_currency} alone is sufficient and no client key is needed.
 * Replaying a create converges on the same row, which is why there is no
 * {@code Idempotency-Key} on that endpoint.
 */
class WalletServiceTests extends PostgresIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private TransferService transfers;

    private UUID customerFor(String name) {
        UUID ownerId = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), ownerId, name);
        return ownerId;
    }

    @Test
    void opensAZeroBalanceWallet() {
        UUID owner = customerFor("Alice");

        WalletService.Created result = walletService.create(owner, "USD");

        assertThat(result.created()).isTrue();
        assertThat(result.wallet().getCurrency()).isEqualTo("USD");
        // Zero, never null: a wallet that has never been paid into still has a
        // balance, and a null here would push the "no money yet" decision onto every
        // caller to get right.
        assertThat(result.wallet().getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void openingTheSameCurrencyTwiceReturnsTheSameWallet() {
        UUID owner = customerFor("Bob");

        WalletService.Created first = walletService.create(owner, "USD");
        WalletService.Created second = walletService.create(owner, "USD");

        // The flag is the useful part. A retry has to be able to tell "you already had
        // this" from "I just created it", or a client that retries after a timeout
        // cannot know whether it now owns a wallet it did not have.
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.wallet().getId()).isEqualTo(first.wallet().getId());
        assertThat(wallets.count()).isEqualTo(1);
    }

    @Test
    void acceptsOneWalletPerCurrency() {
        UUID owner = customerFor("Carol");

        walletService.create(owner, "USD");
        WalletService.Created second = walletService.create(owner, "EUR");

        assertThat(second.created()).isTrue();
        assertThat(second.wallet().getCurrency()).isEqualTo("EUR");
        assertThat(walletService.mine(owner)).hasSize(2);
    }

    /**
     * Lowercase is accepted and normalised, uppercase is what is stored.
     *
     * <p>Checked in Java because {@code chk_wallets_currency} only matches
     * {@code ^[A-Z]{3}$}: left to the database, {@code "usd"} is a constraint violation
     * surfacing as a 500, which tells the caller nothing and invites a bug report
     * instead of a correction.
     */
    @Test
    void normalisesCurrencyCase() {
        UUID owner = customerFor("Dave");

        WalletService.Created result = walletService.create(owner, "eur");

        assertThat(result.wallet().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void rejectsAnythingThatIsNotAThreeLetterCode() {
        UUID owner = customerFor("Erin");

        for (String bad : List.of("US", "USDD", "US1", "", "  ")) {
            assertThatThrownBy(() -> walletService.create(owner, bad))
                    .isInstanceOf(WalletService.InvalidCurrencyException.class)
                    .hasMessageContaining("3-letter");
        }
        assertThatThrownBy(() -> walletService.create(owner, null))
                .isInstanceOf(WalletService.InvalidCurrencyException.class);

        assertThat(wallets.count()).isZero();
    }

    /**
     * A currency code from a known list would catch {@code ZZZ}. It is not enforced:
     * ISO 4217 changes, and a hardcoded list in application code goes stale and starts
     * rejecting valid currencies. Three letters is what the database guarantees, and
     * what is enough to keep a currency column meaningful.
     */
    @Test
    void acceptsAnyThreeLetters() {
        UUID owner = customerFor("Frank");

        assertThat(walletService.create(owner, "ZZZ").created()).isTrue();
    }

    @Test
    void refusesBeforeTheCustomerRowHasArrived() {
        UUID owner = UUID.randomUUID();

        // The customer row is created by consuming UserCreated off auth.events, so a
        // user who has just registered legitimately gets here first. Failing with a
        // clear "not yet" beats a NullPointerException three frames down.
        assertThatThrownBy(() -> walletService.create(owner, "USD"))
                .isInstanceOf(WalletService.NoSuchCustomerException.class);

        assertThat(wallets.count()).isZero();
    }

    /** Two tabs opening the same currency at once must still produce one wallet. */
    @Test
    void concurrentOpensProduceOneWallet() throws Exception {
        UUID owner = customerFor("Grace");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startTogether = new CountDownLatch(1);
            Future<Boolean> a = pool.submit(() -> {
                startTogether.await();
                return walletService.create(owner, "USD").created();
            });
            Future<Boolean> b = pool.submit(() -> {
                startTogether.await();
                return walletService.create(owner, "USD").created();
            });
            startTogether.countDown();

            // Exactly one caller may be told it created the wallet. Two would mean two
            // wallets, which the unique index prevents -- so this is really asserting
            // that the ON CONFLICT clause is doing the arbitrating, not that the
            // constraint is.
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(wallets.count()).isEqualTo(1);
    }

    @Test
    void readsOnlyTheCallersWallets() {
        UUID mine = customerFor("Hank");
        UUID theirs = customerFor("Ivy");
        walletService.create(mine, "USD");
        walletService.create(theirs, "USD");

        assertThat(walletService.mine(mine)).hasSize(1);
        assertThat(walletService.mine(theirs)).hasSize(1);
    }

    @Test
    void historyIsRefusedForSomebodyElsesWallet() {
        UUID mine = customerFor("Jack");
        UUID theirs = customerFor("Kate");
        walletService.create(mine, "USD");
        UUID theirWallet = walletService.create(theirs, "USD").wallet().getId();

        // Throwing rather than returning an empty list: an empty history for a wallet
        // that is not yours is indistinguishable from a brand new one, so a caller
        // would believe they were looking at their own account.
        assertThatThrownBy(() -> walletService.history(mine, theirWallet, 50))
                .isInstanceOf(WalletService.NoSuchWalletException.class);
    }

    @Test
    void historyIsBounded() {
        UUID owner = customerFor("Lena");
        walletService.create(owner, "USD");
        walletService.create(owner, "EUR");
        UUID other = walletService.mine(owner).get(1).getId();

        // A negative or absurd limit is clamped rather than passed through. Pageable
        // with a huge size would ask Postgres for the entire ledger.
        assertThat(walletService.history(owner, other, -5).entries()).isEmpty();
        assertThat(walletService.history(owner, other, 1_000_000).effectiveLimit()).isLessThanOrEqualTo(100);
        assertThat(walletService.history(owner, other, 0).effectiveLimit()).isEqualTo(1);
    }

    /**
     * Newest first, which is the only order anyone reads a statement in.
     *
     * <p>The matching index is {@code (wallet_id, created_at DESC)}, so this order is
     * the one Postgres can serve without a sort. Asking for ascending instead would
     * force a sort over the whole history to return its last ten rows.
     */
    @Test
    void historyReturnsEntriesNewestFirst() {
        UUID owner = customerFor("Mallory");
        UUID payee = customerFor("Payee-10");
        Wallet theirs = walletService.create(payee, "USD").wallet();

        // Seeded with an opening balance rather than credited afterwards: credit() is a
        // @Modifying query and needs an ambient transaction, and these tests
        // deliberately run without one (see PostgresIntegrationTest for why) so that a
        // committed write looks like the real thing.
        Wallet mine = wallets.saveAndFlush(new Wallet(UUID.randomUUID(),
                customers.findByOwnerId(owner).orElseThrow().getId(), "USD",
                new BigDecimal("100.00")));

        transfers.transfer(owner, Transfer.requested(mine.getId(), theirs.getId(),
                new BigDecimal("10.00"), "first"), UUID.randomUUID());
        transfers.transfer(owner, Transfer.requested(mine.getId(), theirs.getId(),
                new BigDecimal("20.00"), "second"), UUID.randomUUID());

        List<WalletEntry> history = walletService.history(owner, mine.getId(), 50).entries();

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getAmount()).isEqualByComparingTo("20.00");
        assertThat(history.get(1).getAmount()).isEqualByComparingTo("10.00");
        assertThat(history.get(0).getBalanceAfter()).isEqualByComparingTo("70.00");
    }
}
