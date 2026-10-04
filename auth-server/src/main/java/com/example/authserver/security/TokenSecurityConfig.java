package com.example.authserver.security;

import com.nimbusds.jose.jwk.JWKSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Beans for issuing tokens and checking passwords.
 *
 * <p>No {@code SecurityFilterChain} bean is defined, deliberately. auth-server is an
 * <em>issuer</em>, not a resource server: it hands out tokens and never needs to
 * authenticate an inbound request, because nothing calls auth-server's own endpoints
 * except a user logging in. Adding the full starter would install a default filter
 * chain that secures every endpoint with HTTP Basic and a generated password — which
 * would be pure confusion here, and would 401 the login endpoint itself.
 */
@Configuration
public class TokenSecurityConfig {

    /**
     * BCrypt at the default strength (10).
     *
     * <p>Why BCrypt and not SHA-256: a fast hash is the wrong tool for passwords.
     * SHA-256 lets an attacker try billions of guesses per second on a GPU; BCrypt is
     * deliberately slow and parameterised, so the same hardware advantage largely
     * disappears. It also salts internally — two users with the same password get
     * different hashes — so a stolen table cannot be attacked with precomputed
     * rainbow tables, and a leak of one hash says nothing about the user's actual
     * password.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Signs with the private key. The key id is attached to the JWK so the encoder and
     * the published JWKS agree — a verifier looks the signing key up by {@code kid},
     * and a mismatch here is a token nothing can verify.
     */
    @Bean
    JwtEncoder jwtEncoder(JwtKeyProvider keys) {
        return NimbusJwtEncoder.withKeyPair(keys.publicKey(), keys.privateKey())
                .algorithm(SignatureAlgorithm.RS256)
                .jwkPostProcessor(jwk -> jwk.keyID(keys.keyId()))
                .build();
    }

    /** Exposed so the JWKS endpoint serves exactly the key the encoder used. */
    @Bean
    JWKSet jwkSet(JwtKeyProvider keys) {
        return keys.publicJwkSet();
    }
}