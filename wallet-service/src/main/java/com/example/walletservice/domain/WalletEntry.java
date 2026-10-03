package com.example.walletservice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One immutable movement in a wallet's history. Rows are only ever inserted --
 * there is no update path, which is what makes the table trustworthy as an audit
 * trail.
 *
 * <p>{@code balanceAfter} snapshots the wallet balance as of this entry. It is
 * denormalised on purpose: summing the entries and comparing against
 * {@code wallets.balance} is how drift between the two is detected.
 */
@Entity
@Table(name = "wallet_entries")
public class WalletEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private EntryType entryType;

    /** Always positive. {@link #entryType} carries the direction. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4,
            columnDefinition = "NUMERIC(19,4)", updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4,
            columnDefinition = "NUMERIC(19,4)", updatable = false)
    private BigDecimal balanceAfter;

    /**
     * Caller-supplied idempotency key, unique when present. A retried debit with
     * the same reference violates the unique index instead of moving the balance
     * twice.
     */
    @Column(name = "reference", length = 64, updatable = false)
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletEntry() {
    }

    public WalletEntry(UUID id, UUID walletId, EntryType entryType, BigDecimal amount,
                       BigDecimal balanceAfter, String reference) {
        this.id = id;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reference = reference;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}