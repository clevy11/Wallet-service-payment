package com.example.authserver.security;

import java.time.Instant;
import java.util.UUID;

import com.example.authserver.domain.User;
import com.example.authserver.support.PostgresIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Account state changes, tested against the database rather than a mock: the whole
 * behaviour is "which row ends up in which state", and a mock cannot observe that.
 */
class AccountServiceTests extends PostgresIntegrationTest {

    @Autowired
    private AccountService accounts;

    @Autowired
    private JwtKeyProvider keyProvider;

    @Autowired
    private TokenRevocationValidator validator;

    @Autowired
    private TokenIssuer tokens;

    @Test
    @DisplayName("deactivation disables the account and revokes its tokens")
    void deactivateWritesBothChanges() {
        User user = anEnabledUser();

        AccountService.Deactivation result = accounts.deactivate(user.getId());

        assertThat(result.enabled()).isFalse();
        assertThat(result.tokenVersion()).isEqualTo(1);

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.getTokenVersion()).isEqualTo(1);
    }

    /**
     * Deactivation must survive its own transaction committing. Verified through a
     * fresh read rather than by inspecting the returned value, because a dirty-check
     * flush that silently failed would still return the right object.
     */
    @Test
    @DisplayName("the change is committed, not just held in memory")
    void theChangeIsCommitted() {
        User user = anEnabledUser();

        accounts.deactivate(user.getId());

        assertThat(users.findById(user.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("deactivating twice keeps incrementing")
    void deactivateIsRepeatable() {
        User user = anEnabledUser();

        accounts.deactivate(user.getId());
        AccountService.Deactivation second = accounts.deactivate(user.getId());

        // Monotonic, and never an error. A client retrying after a lost response must
        // not get an exception and be left unsure whether the first attempt landed.
        assertThat(second.tokenVersion()).isEqualTo(2);
        assertThat(second.enabled()).isFalse();
    }

    @Test
    @DisplayName("reactivating does not resurrect old tokens")
    void reactivateMovesTheCounterForward() {
        User user = anEnabledUser();

        accounts.deactivate(user.getId());
        AccountService.Deactivation result = accounts.reactivate(user.getId());

        assertThat(result.enabled()).isTrue();
        // 2, not back to 0. Restoring the old value would make every token issued
        // before the deactivation valid again, which is the opposite of the point.
        assertThat(result.tokenVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("deactivating an unknown user is an error")
    void unknownUserIsAnError() {
        assertThatThrownBy(() -> accounts.deactivate(UUID.randomUUID()))
                .isInstanceOf(AccountService.NoSuchUserException.class);
    }

    /**
     * A token with no {@code token_version} claim must be refused. Unreachable over
     * HTTP -- the issuer always sets it -- which is exactly why it needs a direct
     * test: it guards against a future token path that forgets the claim and silently
     * produces a token that cannot be revoked.
     */
    @Test
    @DisplayName("a token with no token_version claim is refused")
    void tokenWithoutAVersionIsRefused() {
        User user = anEnabledUser();

        // Assembled rather than decoded, because the issuer cannot produce this: every
        // token it mints carries the claim. That is the point -- this is the token a
        // future code path would produce by forgetting to set it, and such a token
        // must be refused rather than trusted for lacking evidence.
        Jwt withoutClaim = Jwt.withTokenValue("header.payload.signature")
                .header("alg", "RS256")
                .subject(user.getId().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();

        assertThat(validator.validate(withoutClaim).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a token from before a deactivation is refused")
    void staleTokenIsRefused() {
        User user = anEnabledUser();
        Jwt stale = aJwtFor(user);

        assertThat(validator.validate(stale).hasErrors()).isFalse();

        accounts.deactivate(user.getId());

        assertThat(validator.validate(stale).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("a disabled account is refused even at the current version")
    void disabledAccountIsRefusedAtTheCurrentVersion() {
        User user = anEnabledUser();
        Jwt current = aJwtFor(user);

        assertThat(validator.validate(current).hasErrors()).isFalse();

        // Disabled without bumping the version: exactly the half-finished state the
        // AccountService transaction exists to make impossible, written here on
        // purpose. The validator still catches it, because it checks enabled
        // independently of token_version -- so this fails loudly if someone later
        // "simplifies" the validator to compare versions alone.
        //
        // saveAndFlush is required: anEnabledUser() saved outside a transaction, so the
        // entity is detached and a bare disable() would mutate an object the validator
        // never reads. The version is asserted unchanged so the test cannot silently
        // become a duplicate of the stale-token case.
        user.disable();
        users.saveAndFlush(user);
        assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion()).isZero();
        assertThat(validator.validate(current).hasErrors()).isTrue();
    }

    /**
     * A genuinely signed JWT for this user, decoded back into a {@link Jwt}.
     *
     * <p>Built through the real encoder rather than hand-assembled, so the claims the
     * validator sees are exactly the ones the issuer produces -- a hand-built Jwt with
     * only the fields this test remembered to set would pass while the real token
     * failed, which is the more likely direction of error.
     */
    private Jwt aJwtFor(User user) {
        return NimbusJwtDecoder.withPublicKey(keyProvider.publicKey()).build()
                .decode(tokens.issue(user).value());
    }

    private User anEnabledUser() {
        User user = new User(UUID.randomUUID(), "ada", "ada-" + UUID.randomUUID() + "@example.com",
                "$2a$10$notarealhashbutthiscolumnisneververifiedhere");
        users.save(user);
        return user;
    }
}
