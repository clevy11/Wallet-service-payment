package com.example.authserver.security;

import java.util.UUID;

import com.example.authserver.domain.User;
import com.example.authserver.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account lifecycle, distinct from authentication.
 *
 * <p>Separate from {@link LoginService} on purpose. Login is a read that issues a
 * token; this mutates state, and mixing the two invites an accidental
 * {@code readOnly} transaction quietly discarding a revocation.
 */
@Service
public class AccountService {

    private final UserRepository users;

    public AccountService(UserRepository users) {
        this.users = users;
    }

    /**
     * Deactivates an account and revokes its tokens in one transaction.
     *
     * <p>Both steps, atomically, because either alone is a weaker statement than it
     * looks:
     *
     * <ul>
     *   <li>{@code enabled = false} alone stops future logins but leaves every
     *       already-issued token working for up to the full TTL — an account
     *       deactivated for being compromised would stay usable by the attacker for
     *       another 15 minutes.</li>
     *   <li>{@code token_version++} alone kills existing tokens while leaving the
     *       user able to log straight back in and get a fresh one, which is not what
     *       "deactivate" means to anybody asking for it.</li>
     * </ul>
     *
     * <p>There is deliberately no lazy delete here, and no row removal. Three reasons,
     * each of which bites in this specific schema:
     *
     * <ol>
     *   <li>{@code walletdb.customers.owner_id} references this user's id from
     *       another database, with no foreign key to cascade. Deleting the row leaves
     *       an orphan that no constraint will ever report.</li>
     *   <li>The ledger is the audit trail. An entry whose owner no longer resolves is
     *       worse than an entry owned by an anonymous id.</li>
     *   <li>{@code UNIQUE(username)} would permanently burn the username, so nobody
     *       could ever register it again.</li>
     * </ol>
     *
     * <p>For a genuine erasure request — a "right to be forgotten" — the id and the
     * row survive and only the personal fields are overwritten. That is a separate
     * operation from this one, and it is also not complete until wallet-service has
     * dropped its copy of {@code display_name}, which can only happen via an event.
     *
     * @return the account's state after the change, read from the managed entity so
     *         the response cannot report something the database does not hold
     */
    @Transactional
    public Deactivation deactivate(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NoSuchUserException(userId));

        user.disable();
        user.revokeIssuedTokens();
        // No save() call. The dirty-checking flush at commit writes both changes
        // because the entity is managed inside this transaction; an explicit save
        // would be a redundant UPDATE, and on an assigned @Id would route through
        // merge() and issue a pointless SELECT first.
        return new Deactivation(user.isEnabled(), user.getTokenVersion());
    }

    /** Reactivates an account and revokes tokens, since they were all revoked. */
    @Transactional
    public Deactivation reactivate(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NoSuchUserException(userId));

        user.enable();
        // Deliberately not restored to its old value: tokens issued before
        // deactivation must stay dead, and there is no way to tell from the counter
        // alone which values were already used. Moving forward is the only safe
        // direction.
        user.revokeIssuedTokens();
        return new Deactivation(user.isEnabled(), user.getTokenVersion());
    }

    /** Both facts, because both change and a caller may act on either. */
    public record Deactivation(boolean enabled, int tokenVersion) {
    }

    public static class NoSuchUserException extends RuntimeException {
        public NoSuchUserException(UUID userId) {
            super("no user " + userId);
        }
    }
}