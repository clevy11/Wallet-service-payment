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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One immutable movement in a wallet's history. Rows are only ever inserted --
 * there is no update path, which is what makes the table trustworthy as an audit
 * trail. So no field is settable: {@code @Setter} is declared at class level only
 * so that each field's {@code @Setter(AccessLevel.NONE)} below is explicit about
 * closing it off, rather than relying on nobody noticing the annotation is gone.
 *
 * <p>{@code balanceAfter} snapshots the wallet balance as of this entry. It is
 * denormalised on purpose: summing the entries and comparing against
 * {@code wallets.balance} is how drift between the two is detected.
 *
 * <p>See {@link Customer} for why these entities use {@code @Getter @Setter}
 * instead of {@code @Data}.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "wallet_entries")
public class WalletEntry {

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private EntryType entryType;

    /** Always positive. {@link #entryType} carries the direction. */
    @Setter(AccessLevel.NONE)
    @Column(name = "amount", nullable = false, precision = 19, scale = 4,
            columnDefinition = "NUMERIC(19,4)", updatable = false)
    private BigDecimal amount;

    @Setter(AccessLevel.NONE)
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4,
            columnDefinition = "NUMERIC(19,4)", updatable = false)
    private BigDecimal balanceAfter;

    /**
     * The transfer this entry is one leg of, or null for a DEPOSIT or ADJUSTMENT,
     * which has no counterparty.
     *
     * <p>What makes an idempotent replay answerable. A retry that finds an existing
     * entry knows the transfer happened; it still needs the transfer's id to report
     * back, and nothing else on this row carries it.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "transfer_id", updatable = false)
    private UUID transferId;

    /**
     * Caller-supplied idempotency key, unique when present. A retried debit with the
     * same reference violates the unique index instead of moving the balance twice.
     * Derived from the client's own key by {@code TransferLeg}, not stored raw: the
     * column is 64 characters and a caller's key has no length limit, so storing it
     * directly would silently truncate and collide unrelated transfers.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "reference", length = 64, updatable = false)
    private String reference;

    @Setter(AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public WalletEntry(UUID id, UUID walletId, EntryType entryType, BigDecimal amount,
                       BigDecimal balanceAfter, String reference, UUID transferId) {
        this.id = id;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reference = reference;
        this.transferId = transferId;
        this.createdAt = Instant.now();
    }
}