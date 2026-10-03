package com.example.walletservice.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer is the business-side projection of a person authenticated by
 * auth-server. The same human exists in two databases: {@code authdb.users}
 * holds credentials, this table holds everything wallet-service cares about.
 * The link is {@code ownerId}, which equals the JWT {@code sub} claim.
 *
 * <p>It is a plain column rather than a foreign key because authdb and walletdb
 * are separate databases, and Postgres cannot enforce a referential constraint
 * across them. The link is established by a {@code UserCreated} event on
 * {@code auth.events}, and guarded by the unique constraint on {@code owner_id}.
 *
 * <p><b>On the Lombok annotations:</b> deliberately {@code @Getter @Setter}
 * rather than {@code @Data}. {@code @Data} bundles in {@code @ToString} and
 * {@code @EqualsAndHashCode} over every field, which is wrong for a mutable JPA
 * entity: the generated {@code equals}/{@code hashCode} include mutable state
 * and the {@code @Version} counter, so an instance's {@code hashCode} changes
 * the moment it is saved. Anything held in a {@code HashSet} or used as a map key
 * is then corrupted. Hibernate uses the identifier for identity, not field
 * equality. {@code @Data} is safe on DTOs, where the fields are immutable.
 *
 * <p>{@link NoArgsConstructor} is {@code PROTECTED} rather than
 * {@code public}: JPA needs a no-arg constructor to hydrate an entity, but
 * nothing in application code should be able to call it.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "customers")
public class Customer {

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @Column(name = "owner_id", nullable = false, updatable = false, unique = true)
    private UUID ownerId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Customer(UUID id, UUID ownerId, String displayName, String defaultCurrency) {
        this.id = id;
        this.ownerId = ownerId;
        this.displayName = displayName;
        this.defaultCurrency = defaultCurrency;
        this.createdAt = Instant.now();
    }
}