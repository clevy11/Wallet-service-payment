package com.example.walletservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The two sides of a transfer, and how each is recorded.
 *
 * <p>A single transfer writes two {@code wallet_entries} rows, and both need a
 * {@code reference} derived from the caller's idempotency key. They cannot share one
 * value: {@code uq_wallet_entries_reference} is unique, so the second leg would
 * collide with the first and the transfer would fail against itself.
 *
 * <p><b>Why the key is hashed rather than stored.</b> {@code reference} is
 * {@code VARCHAR(64)} and a caller's idempotency key has no length limit -- a 200
 * character key would be truncated by the database, and two keys sharing the first 64
 * characters would then be treated as the same transfer. Hashing gives a fixed-width
 * 64-character digest, so the column can hold it exactly and the uniqueness is over
 * the whole key rather than a prefix of it.
 *
 * <p>It also keeps caller-supplied data out of the ledger. These rows are the audit
 * trail for money, and a client-chosen string in that table is a stored-injection
 * surface that every future reader of the ledger has to remember to sanitise.
 */
public enum TransferLeg {

    /** Money leaving {@code source}. */
    OUT("out"),
    /** Money arriving at {@code destination}. */
    IN("in");

    private final String suffix;

    TransferLeg(String suffix) {
        this.suffix = suffix;
    }

    public com.example.walletservice.domain.EntryType entryType() {
        return this == OUT
                ? com.example.walletservice.domain.EntryType.TRANSFER_OUT
                : com.example.walletservice.domain.EntryType.TRANSFER_IN;
    }

    /**
     * A distinct, fixed-width reference for this leg.
     *
     * <p>Both legs are derived from the same key, so a retry of either finds the row
     * that already exists. Only the outgoing leg is relied on to detect the duplicate
     * -- but both are written, because the unique index applies to all non-null
     * references and the constraint has to be satisfied by every row inserted.
     */
    public String referenceFor(String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((idempotencyKey + ":" + suffix)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform. If it is missing, the JVM is
            // unusable and failing here is the least of the problems.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}