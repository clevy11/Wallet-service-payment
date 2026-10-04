package com.example.authserver.security;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.authserver.domain.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the asymmetric story actually holds, rather than merely being the shape of
 * the code.
 *
 * <p>The decisive test is {@link #tokenVerifiesAgainstThePublicKeyAlone()}: a decoder
 * built from <em>only</em> the published public key validates a token signed with the
 * private one. That is the entire justification for a keypair over a shared secret,
 * and if the private half ever leaked into the JWKS, this is the test that would
 * still pass while the security property was gone. The JWKS test in
 * {@code JwtKeyProviderTests} is the other half of that pair.
 *
 * <p>No Spring context: wiring these by hand keeps the test fast and, more usefully,
 * forces the test to name the encoder and decoder explicitly instead of inheriting
 * whatever the context happens to provide.
 */
class TokenIssuerTests {

    private static final Duration TTL = Duration.ofMinutes(15);

    private JwtKeyProvider keys;
    private TokenIssuer issuer;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        keys = new JwtKeyProvider(dir.toString());
        JwtEncoderHolder holder = new JwtEncoderHolder(keys);
        issuer = new TokenIssuer(holder.encoder(), keys, "http://localhost:8090", TTL);
    }

    @Test
    @DisplayName("a token verifies against the public key alone")
    void tokenVerifiesAgainstThePublicKeyAlone() {
        User user = aUser();

        String token = issuer.issue(user).value();

        // No private key anywhere in this decoder. If verification succeeds, the
        // signature really is checkable by a service that only holds the public half.
        JwtDecoder verifier = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();
        Jwt verified = verifier.decode(token);

        assertThat(verified.getSubject()).isEqualTo(user.getId().toString());
    }

    @Test
    @DisplayName("the claims carry what downstream services need")
    void carriesTheUsefulClaims() {
        User user = aUser();
        Instant before = Instant.now();

        TokenIssuer.IssuedToken issued = issuer.issue(user);
        Jwt jwt = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build().decode(issued.value());

        // sub is the link to wallet-service's customers.owner_id.
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getIssuer()).hasToString("http://localhost:8090");
        assertThat(jwt.getAudience()).containsExactly("wallet-service");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsString("roles")).isEqualTo("USER");
        // Not getClaimAsInteger: ClaimAccessor has no such method, and JSON numbers
        // decode to Number, not Integer.
        assertThat(((Number) jwt.getClaim("token_version")).intValue()).isZero();

        // Expiry is the access-token TTL away, not some default.
        assertThat(jwt.getExpiresAt()).isAfter(before.plus(TTL).minusSeconds(5));
        assertThat(jwt.getExpiresAt()).isBefore(Instant.now().plus(TTL).plusSeconds(5));
        // exp is truncated to whole seconds: RFC 7519 NumericDate has no sub-second
        // component. So the token actually dies up to 0.999s before expiresAt says,
        // and expires_in: 900 can over-promise by under a second. Truncation errs
        // toward expiring early, which is the safe direction -- the opposite rounding
        // would keep a token alive past its advertised lifetime.
        assertThat(issued.expiresAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(jwt.getExpiresAt());
    }

    /**
     * Without {@code kid} a verifier holding several keys during a rotation cannot
     * tell which one signed a token, and the rotation turns into an outage.
     */
    @Test
    @DisplayName("the header names the signing key")
    void headerCarriesTheKeyId() {
        Jwt jwt = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build()
                .decode(issuer.issue(aUser()).value());

        assertThat(jwt.getHeaders().get("kid")).isEqualTo(keys.keyId());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
    }

    @Test
    @DisplayName("every token is distinct")
    void tokensAreNotIdentical() {
        User user = aUser();

        assertThat(issuer.issue(user).value()).isNotEqualTo(issuer.issue(user).value());
    }

    /**
     * The property a signature exists for. Editing any claim invalidates the token,
     * so a caller cannot talk itself into another {@code sub} or a longer {@code exp}.
     */
    @Test
    @DisplayName("a tampered token is rejected")
    void tamperedTokenIsRejected() {
        String token = issuer.issue(aUser()).value();

        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "Zm9yZ2Vk";

        assertThatThrownBy(() -> NimbusJwtDecoder.withPublicKey(keys.publicKey()).build().decode(tampered))
                .isNotNull();
    }

    /**
     * A token from a different keypair must not verify, even though it is
     * structurally identical. This is the negative of the keypair property.
     */
    @Test
    @DisplayName("a token signed by another issuer's key is rejected")
    void foreignSignatureIsRejected(@TempDir Path otherDir) {
        String foreignToken = new TokenIssuer(new JwtEncoderHolder(new JwtKeyProvider(otherDir.toString()))
                .encoder(), new JwtKeyProvider(otherDir.toString()), "http://evil", TTL)
                .issue(aUser()).value();

        assertThatThrownBy(() -> NimbusJwtDecoder.withPublicKey(keys.publicKey()).build().decode(foreignToken))
                .isNotNull();
    }

    @Test
    @DisplayName("token_version travels in the token, so bumping the column revokes it")
    void tokenVersionIsCarried() {
        User user = aUser();
        user.revokeIssuedTokens();
        user.revokeIssuedTokens();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build()
                .decode(issuer.issue(user).value());

        // Nothing consumes this yet -- enforcement lands with the resource-server
        // slice -- but it has to be in the token from day one, because tokens issued
        // before it existed cannot be revoked retroactively.
        assertThat(((Number) jwt.getClaim("token_version")).intValue()).isEqualTo(2);
    }

    private User aUser() {
        return new User(UUID.randomUUID(), "ada", "ada@example.com", "irrelevant-hash");
    }

    /** Builds the same encoder the Spring config does, without a context. */
    private record JwtEncoderHolder(org.springframework.security.oauth2.jwt.JwtEncoder encoder) {

        JwtEncoderHolder(JwtKeyProvider keys) {
            this(org.springframework.security.oauth2.jwt.NimbusJwtEncoder
                    .withKeyPair(keys.publicKey(), keys.privateKey())
                    .algorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                    .jwkPostProcessor(jwk -> jwk.keyID(keys.keyId()))
                    .build());
        }
    }
}