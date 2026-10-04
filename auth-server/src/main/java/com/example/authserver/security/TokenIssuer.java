package com.example.authserver.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.example.authserver.domain.User;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Mints signed access tokens.
 *
 * <p>The claim choices are the design, so they are worth reading rather than
 * skimming:
 *
 * <ul>
 *   <li>{@code sub} is {@code users.id}, the same UUID that wallet-service stores as
 *       {@code customers.owner_id}. That equality is what lets a later authorisation
 *       check answer "whose wallet is this?" with no join and no lookup table.</li>
 *   <li>{@code token_version} carries the value of the column at issue time. The
 *       column is the only way to revoke a stateless token: bump it and every token
 *       ever issued to that user stops verifying. Without it, the honest answer to a
 *       stolen token is "wait for expiry".</li>
 *   <li>{@code jti} gives the token an identity of its own, so it can be correlated
 *       in logs and, later, listed in a revocation table.</li>
 *   <li>{@code kid} in the header tells a verifier <em>which</em> key signed this.
 *       Without it a verifier holding several keys (during a rotation) cannot choose,
 *       and a rotation becomes an outage.</li>
 * </ul>
 */
@Component
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final JwtKeyProvider keys;
    private final String issuer;
    private final Duration ttl;

    public TokenIssuer(JwtEncoder encoder, JwtKeyProvider keys,
                       @org.springframework.beans.factory.annotation.Value("${app.security.issuer}") String issuer,
                       @org.springframework.beans.factory.annotation.Value("${app.security.token-ttl}") Duration ttl) {
        this.encoder = encoder;
        this.keys = keys;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public IssuedToken issue(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                // The audience is the set of services allowed to accept this token.
                // Without it, a token minted for one service is equally valid at every
                // other service that trusts the same issuer.
                .audience(java.util.List.of("wallet-service"))
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", user.getRoles())
                .claim("token_version", user.getTokenVersion())
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keys.keyId())
                .build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt, ttl);
    }

    public record IssuedToken(String value, Instant expiresAt, Duration ttl) {
    }
}