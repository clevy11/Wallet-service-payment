package com.example.walletservice.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer is the business-side projection of a person authenticated by
 * auth-server. The same human exists in two databases: {@code authdb.users}
 * holds credentials, this table holds everything wallet-service cares about.
 * The link is {@link #ownerId}, which equals the JWT {@code sub} claim.
 *
 * <p>It is a plain column rather than a foreign key because authdb and walletdb
 * are separate databases, and Postgres cannot enforce a referential constraint
 * across them. The link is established by a {@code UserCreated} event on
 * {@code auth.events}, and guarded by the unique constraint below.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false, unique = true)
    private UUID ownerId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {
    }

    public Customer(UUID id, UUID ownerId, String displayName, String defaultCurrency) {
        this.id = id;
        this.ownerId = ownerId;
        this.displayName = displayName;
        this.defaultCurrency = defaultCurrency;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}