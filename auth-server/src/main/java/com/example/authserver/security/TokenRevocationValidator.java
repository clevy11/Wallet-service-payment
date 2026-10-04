package com.example.authserver.security;

import com.example.authserver.domain.User;
import com.example.authserver.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rejects a structurally valid token that is no longer wanted.
 *
 * <p>This is the piece that makes deactivation mean anything. A JWT is
 * <em>self-contained</em>: it carries its own expiry and nothing else, and no
 * standard validator reads a database. So the two claims that revocation depends on
 * are invisible unless something checks them:
 *
 * <ul>
 *   <li>{@code token_version} — compared against {@code users.token_version}. The
 *       token remembers the value from issue time; bumping the column invalidates
 *       every token ever issued, instantly, including ones minted a millisecond ago.
 *       This is the whole reason that claim exists.</li>
 *   <li>{@code enabled} — not a claim at all. A token issued while the account was
 *       active says nothing about whether it still is, so the column has to be read
 *       as well.</li>
 * </ul>
 *
 * <p>The cost is the point worth noticing: this runs on <em>every authenticated
 * request</em>, so deactivating an account immediately revokes access at the price of
 * one index lookup per request and one hard dependency on authdb being reachable. A
 * failing authdb means every authenticated request fails — deactivation buys
 * correctness with availability. The alternatives are a denylist of revoked token ids
 * (same lookup, larger table) or a short TTL with no revocation at all (no lookup, no
 * instant effect). That trade-off is why the claim is opt-in per service: a service
 * that tolerates a 15-minute window can simply not install this validator.
 *
 * <p>Runs on a read-only transaction, and deliberately returns the same failure for
 * every cause — an unknown user, a stale version and a disabled account are all
 * {@code invalid_token}. Distinguishing them in the error would turn the validator
 * into a user-existence oracle.
 */
@Component
public class TokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationValidator.class);

    /**
     * RFC 6750 defines {@code invalid_token} for a token that is rejected. The
     * description must stay generic for the same reason the error code is.
     */
    private static final OAuth2Error REVOKED =
            new OAuth2Error("invalid_token", "token is no longer valid", null);

    private final UserRepository users;

    public TokenRevocationValidator(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return users.findById(java.util.UUID.fromString(token.getSubject()))
                .filter(User::isEnabled)
                .filter(user -> sameVersion(user, token))
                .map(user -> OAuth2TokenValidatorResult.success())
                .orElseGet(() -> {
                    log.info("rejected a token for subject {}: disabled or superseded", token.getSubject());
                    return OAuth2TokenValidatorResult.failure(REVOKED);
                });
    }

    private boolean sameVersion(User user, Jwt token) {
        Object claim = token.getClaims().get("token_version");
        // A token with no token_version claim predates revocation and cannot be
        // checked, so it is refused rather than trusted. Trusting a token because a
        // claim is *missing* would let an attacker delete the claim -- it is only
        // skipped because the signature covers the whole payload.
        if (!(claim instanceof Number claimed)) {
            return false;
        }
        return claimed.intValue() == user.getTokenVersion();
    }
}