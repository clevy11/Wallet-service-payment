package com.example.walletservice.service;

import java.util.List;
import java.util.UUID;

import com.example.walletservice.domain.Customer;
import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletEntry;
import com.example.walletservice.repository.CustomerRepository;
import com.example.walletservice.repository.WalletEntryRepository;
import com.example.walletservice.repository.WalletRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening wallets and reading them back.
 *
 * <p>Separate from {@link TransferService} because creation is not a ledger operation:
 * it moves no money, writes no entries and announces nothing. Folding it in would put
 * an outbox row and a balance update on the path of a request that needs neither.
 */
@Service
public class WalletService {

    /** Bounded on purpose: an unbounded history endpoint is an outage for a busy wallet. */
    private static final int MAX_ENTRIES_PER_PAGE = 100;

    private final CustomerRepository customers;
    private final WalletRepository wallets;
    private final WalletEntryRepository entries;

    public WalletService(CustomerRepository customers, WalletRepository wallets,
                         WalletEntryRepository entries) {
        this.customers = customers;
        this.wallets = wallets;
        this.entries = entries;
    }

    /**
     * Opens a wallet for the customer behind the token, or returns the one already there.
     *
     * <p>Idempotent on {@code (customer_id, currency)} via a single
     * {@code INSERT ... ON CONFLICT DO NOTHING}, so a double-click, a retry, or two
     * browser tabs racing each other cannot produce two wallets in the same currency.
     * A SELECT-then-INSERT would leave a window between the two statements that the
     * unique index then turns into a 500 rather than a second wallet.
     *
     * <p>The customer id is taken from the token, never from the request. A body
     * carrying it would let any authenticated caller open a wallet on somebody else's
     * account, and the ownership check would be a parameter the caller controls.
     *
     * @return the wallet, and whether this call is what created it
     */
    @Transactional
    public Created create(UUID ownerId, String currency) {
        String normalised = normaliseCurrency(currency);

        Customer customer = customers.findByOwnerId(ownerId)
                .orElseThrow(() -> new NoSuchCustomerException(ownerId));

        int created = wallets.createIfAbsent(UUID.randomUUID(), customer.getId(), normalised);
        Wallet wallet = wallets.findByCustomerIdAndCurrency(customer.getId(), normalised)
                .orElseThrow(() -> new IllegalStateException(
                        "wallet vanished immediately after creation"));

        return new Created(wallet, created == 1);
    }

    @Transactional(readOnly = true)
    public List<Wallet> mine(UUID ownerId) {
        UUID customerId = customers.findByOwnerId(ownerId)
                .orElseThrow(() -> new NoSuchCustomerException(ownerId))
                .getId();
        return wallets.findByCustomerId(customerId);
    }

    /**
     * Reads a wallet's history.
     *
     * <p>The wallet is fetched through {@code findByCustomerId} rather than by id. It
     * looks redundant, but a plain {@code findById} followed by an ownership check has
     * the same two-step shape as every other check-then-act in this codebase: the id is
     * attacker-supplied, so the check must not be something a caller can skip by
     * reaching a different method. Scoping the query by customer makes an unowned
     * wallet simply absent.
     */
    @Transactional(readOnly = true)
    public History history(UUID ownerId, UUID walletId, int limit) {
        UUID customerId = customers.findByOwnerId(ownerId)
                .orElseThrow(() -> new NoSuchCustomerException(ownerId))
                .getId();

        Wallet wallet = wallets.findByCustomerId(customerId).stream()
                .filter(candidate -> candidate.getId().equals(walletId))
                .findFirst()
                .orElseThrow(() -> new NoSuchWalletException(walletId));

        int capped = Math.min(Math.max(limit, 1), MAX_ENTRIES_PER_PAGE);
        return new History(wallet, entries.findByWalletIdOrderByCreatedAtDesc(walletId,
                PageRequest.of(0, capped)), capped);
    }

    /**
     * Uppercase and length-checked in Java rather than left to the database.
     *
     * <p>{@code chk_wallets_currency} would reject {@code "usd"} with a constraint
     * violation surfacing as a 500. The check exists in the schema as the backstop; this
     * is the one that produces a useful 400 and tells the caller what was wrong.
     */
    private static String normaliseCurrency(String currency) {
        if (currency == null || !currency.matches("^[A-Za-z]{3}$")) {
            throw new InvalidCurrencyException(currency);
        }
        return currency.toUpperCase(java.util.Locale.ROOT);
    }

    public record Created(Wallet wallet, boolean created) {
    }

    /**
     * @param effectiveLimit what was actually applied, which may be lower than what
     *                       the caller asked for. Reported so a client can tell a
     *                       short ledger from a truncated one -- echoing the
     *                       requested value instead would let a caller believe it had
     *                       seen everything when the clamp had hidden rows
     */
    public record History(Wallet wallet, List<WalletEntry> entries, int effectiveLimit) {
    }

    public static class NoSuchCustomerException extends RuntimeException {
        public NoSuchCustomerException(UUID ownerId) {
            super("no customer for " + ownerId);
        }
    }

    public static class NoSuchWalletException extends RuntimeException {
        public NoSuchWalletException(UUID walletId) {
            super("no wallet " + walletId);
        }
    }

    public static class InvalidCurrencyException extends RuntimeException {
        public InvalidCurrencyException(String currency) {
            super("currency must be a 3-letter code, got: " + currency);
        }
    }
}