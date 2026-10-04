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

    /**
     * Whether the account may authenticate at all.
     *
     * <p>Not the same question as whether a particular token is still valid, which is
     * {@link #tokenVersion}. One is a property of the account, the other of the
     * credential, and they change for different reasons: disabling blocks new logins,
     * bumping the version kills tokens already in someone's hand.
     *
     * <p>Setter withheld like {@code id}: flipping this by assignment would make the
     * two-word version {@code setEnabled(false)} the easy way to do it, and the
     * pairing with {@link #revokeIssuedTokens()} is the part that must not be
     * forgotten.
     */
    @Setter(AccessLevel.NONE)
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

    /**
     * Revokes every token ever issued to this user, in one step.
     *
     * <p>A method rather than a setter for the same reason {@code OutboxEvent} exposes
     * {@code markPublished} instead of {@code setPublishedAt}: the column is not
     * arbitrary state, it is a counter with one legal transition, and the legal
     * transition is revocation. A setter would let anything increment it by one for
     * no reason, or worse, set it back down and un-revoke a compromised account.
     *
     * <p>Monotonic on purpose — going backwards would resurrect tokens the user was
     * told were dead.
     */
    public void revokeIssuedTokens() {
        this.tokenVersion++;
        this.updatedAt = Instant.now();
    }

    /**
     * Stops the account authenticating.
     *
     * <p>Note what this does <em>not</em> do: it leaves existing tokens working.
     * Callers that mean "this person is locked out now" must also call
     * {@link #revokeIssuedTokens()}; {@code AccountService} does both, in one
     * transaction.
     */
    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.enabled = true;
        this.updatedAt = Instant.now();
    }
}