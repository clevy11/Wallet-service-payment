package com.example.authserver.web;

import java.util.UUID;

import com.example.authserver.domain.User;
import com.example.authserver.repository.UserRepository;
import com.example.authserver.security.AccountService;
import com.example.authserver.security.JwtKeyProvider;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account behind the token on the request. Every path here requires a valid,
 * non-revoked token — the filter chain permits only login, register and the JWKS.
 */
@RestController
@RequestMapping("/auth/users/me")
public class CurrentUserController {

    private final AccountService accounts;
    private final UserRepository users;
    private final JwtKeyProvider keys;

    public CurrentUserController(AccountService accounts, UserRepository users, JwtKeyProvider keys) {
        this.accounts = accounts;
        this.users = users;
        this.keys = keys;
    }

    @GetMapping
    public Profile me(@AuthenticationPrincipal Jwt jwt) {
        User user = users.findById(UUID.fromString(jwt.getSubject())).orElseThrow();
        return new Profile(
                user.getUsername(),
                user.getEmail(),
                user.getRoles(),
                user.isEnabled(),
                user.getTokenVersion(),
                // Echoed so a caller can tell whether its cached token predates a
                // rotation: a mismatch means it should refresh rather than assume it
                // is still current.
                keys.keyId());
    }

    /**
     * Self-service deactivation, so the target is read from the token and never from
     * the body. A user id in the request would mean any authenticated caller could
     * deactivate anyone else — the ownership check would be a parameter the caller
     * controls, which is not a check at all.
     *
     * <p>Returns the new version so the client can see the counter move. It is
     * returned rather than hidden because the caller is about to be logged out and
     * deserves to know why: this token stops working on the next request.
     */
    @PostMapping("/deactivate")
    public ResponseEntity<Deactivated> deactivate(@AuthenticationPrincipal Jwt jwt) {
        AccountService.Deactivation state = accounts.deactivate(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok(new Deactivated(state.enabled(), state.tokenVersion()));
    }

    // Deliberately absent: the reverse operation. Reactivation belongs to a support
    // action with an audit trail and proof-of-identity, not to a public endpoint on
    // the account's own API -- otherwise anyone who can authenticate can undo a
    // deactivation in one call, and a deactivation protects against exactly that.
    // With no mapping here, POST /auth/users/me/reactivate simply 405s.

    public record Profile(
            String username,
            String email,
            String roles,
            boolean enabled,
            int tokenVersion,
            String signingKeyId) {
    }

    public record Deactivated(boolean enabled, int tokenVersion) {
    }
}