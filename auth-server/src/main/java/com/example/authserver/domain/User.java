package com.example.authserver.domain;

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
 * An account that can authenticate against auth-server.
 *
 * <p>The id is generated here, in auth-server, and becomes the JWT {@code sub}
 * claim. Downstream, wallet-service copies that same value into
 * {@code customers.owner_id}. Neither service owns the other's row: the id is a
 * value carried across a boundary, not a foreign key, because authdb and
 * walletdb are separate databases.
 *
 * <p>See {@link OutboxEvent} for the reason this entity uses {@code @Getter}
 * rather than {@code @Data}.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User {

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @Column(name = "username", nullable = false, length = 64, updatable = false)
    private String username;

    @Setter(AccessLevel.NONE)
    @Column(name = "email", nullable = false, length = 255, updatable = false)
    private String email;

    /**
     * BCrypt/argon2 hash. Never plaintext, never reversible: a leaked table must
     * not hand an attacker working credentials. Not exposed off the entity.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "roles", nullable = false, length = 255)
    private String roles = "USER";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Bumped to invalidate every token ever issued to this user: each JWT
     * carries the value from issue time, and a request is rejected when it no
     * longer matches this column. Cheaper than maintaining a denylist of revoked
     * token ids, at the cost of one read per authenticated request.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Setter(AccessLevel.NONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(UUID id, String username, String email, String passwordHash) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
}