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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A wallet holds a balance in exactly one currency.
 *
 * <p>The balance is a cached running total of {@link WalletEntry} rows. Both are
 * written in the same database transaction, so they cannot disagree, and the sum
 * of a wallet's entries can be compared against the balance as an audit.
 *
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "wallets")
public class Wallet {

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    // Never double. BigDecimal is exact base-10 arithmetic; a double would
    // accumulate error across repeated arithmetic and drift away from the sum of
    // the ledger entries.
    @Column(name = "balance", nullable = false, precision = 19, scale = 4,
            columnDefinition = "NUMERIC(19,4)")
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WalletStatus status = WalletStatus.ACTIVE;

    /**
     * Optimistic locking counter for read-modify-write cycles: Hibernate appends
     * {@code WHERE version = ?} and bumps the column, so a concurrent update
     * fails with an optimistic lock exception instead of silently overwriting.
     *
     * <p>Note for later slices: a bulk {@code @Modifying} JPQL update bypasses
     * this, because Hibernate only manages the version for entity loads it
     * controls. The atomic conditional {@code UPDATE ... WHERE balance >= ?}
     * used for debit relies on the database's row lock instead, which is why that
     * query is safe without this counter.
     */
    @Setter(AccessLevel.NONE)
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Wallet(UUID id, UUID customerId, String currency, BigDecimal openingBalance) {
        this.id = id;
        this.customerId = customerId;
        this.currency = currency;
        this.balance = openingBalance;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

}