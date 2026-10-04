package com.example.walletservice.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Signals that a transfer could not be completed for a stated business reason.
 *
 * <p>Separate from the framework's exception types on purpose. These are rules of
 * this domain -- there is not enough money, the wallets hold different currencies --
 * and coupling them to a framework or a JDBC exception would leak both into every
 * caller and make the set of legitimate failures impossible to enumerate.
 */
public class TransferRejected extends RuntimeException {

    private final Reason reason;

    public TransferRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        /** No wallet for that id, or it belongs to somebody else. */
        UNKNOWN_WALLET,
        /** Amount is not positive. Never reaches the database. */
        INVALID_AMOUNT,
        /** The two wallets hold different currencies. No FX in this system. */
        CURRENCY_MISMATCH,
        /** Not enough money. The database decided, not a Java check. */
        INSUFFICIENT_FUNDS,
        /** The source or destination wallet is frozen or closed. */
        WALLET_NOT_ACTIVE
    }

    public static TransferRejected insufficientFunds(UUID walletId, BigDecimal requested) {
        return new TransferRejected(Reason.INSUFFICIENT_FUNDS,
                "wallet %s has insufficient funds for %s".formatted(walletId, requested));
    }
}