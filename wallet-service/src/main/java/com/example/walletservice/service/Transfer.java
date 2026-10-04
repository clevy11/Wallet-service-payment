package com.example.walletservice.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One movement of money between two wallets, as requested.
 *
 * <p>A record, deliberately: it is an immutable description of an intention, not a
 * mutable entity with identity. Nothing here has a lifecycle, so there is nothing for
 * a setter to corrupt.
 *
 * @param idempotencyKey the caller's own key, used to make a retry safe. Required:
 *                       without it a client that times out and retries has no way to
 *                       avoid paying twice, because "debit 50" and "debit 50 again"
 *                       are indistinguishable by business key
 * @param transferId     assigned once the transfer is known to be new, so the caller's
 *                       retry of the same key returns the same id instead of a second
 *                       transfer
 */
public record Transfer(
        UUID sourceWalletId,
        UUID destinationWalletId,
        BigDecimal amount,
        String idempotencyKey,
        UUID transferId) {

    /**
     * Mirrors {@code NUMERIC(19,4)}. The amount is normalised to it on the way in so
     * that every amount in this system -- request, ledger, event payload and JSON
     * response -- carries the same scale. Left alone, a caller sending {@code 30.00}
     * and a balance read back from the column would see {@code 30.00} and
     * {@code 70.0000} in the same response, and any client doing arithmetic on those
     * strings has to guess which convention applies.
     */
    static final int MONEY_SCALE = 4;

    public Transfer {
        if (sourceWalletId == null || destinationWalletId == null) {
            throw new TransferRejected(TransferRejected.Reason.UNKNOWN_WALLET,
                    "both wallets are required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new TransferRejected(TransferRejected.Reason.INVALID_AMOUNT,
                    "amount must be positive");
        }
        if (amount.scale() > MONEY_SCALE) {
            // Rejected rather than rounded. The column is NUMERIC(19,4), so Postgres
            // would round silently and the payee would receive a different amount than
            // the one the caller asked for -- with nothing in the response to say so.
            // Rounding money to fit is how 0.005 becomes an argument.
            throw new TransferRejected(TransferRejected.Reason.INVALID_AMOUNT,
                    "amount has more than %d decimal places".formatted(MONEY_SCALE));
        }
        amount = amount.setScale(MONEY_SCALE);
        if (sourceWalletId.equals(destinationWalletId)) {
            // Legal in a ledger, pointless in a product: it moves money to itself and
            // writes two entries that cancel. Rejected here so it cannot be used to
            // pad an account's history.
            throw new TransferRejected(TransferRejected.Reason.INVALID_AMOUNT,
                    "source and destination must differ");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new TransferRejected(TransferRejected.Reason.INVALID_AMOUNT,
                    "Idempotency-Key is required");
        }
    }

    /** A first attempt: no id yet. */
    public static Transfer requested(UUID source, UUID destination, BigDecimal amount, String key) {
        return new Transfer(source, destination, amount, key, null);
    }
}