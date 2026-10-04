package com.example.authserver.web;

import com.nimbusds.jose.jwk.JWKSet;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the signing keypair.
 *
 * <p>Unauthenticated by design, and it has to be: a service that has just started
 * holds no token yet, so it cannot present credentials to fetch the key it needs in
 * order to validate tokens. The key is public, so publishing it grants nothing.
 *
 * <p>Only the public key ever leaves. Serving the private key here would let anyone
 * mint a token for any user — including one whose {@code token_version} was just
 * bumped to revoke their old tokens.
 */
@RestController
public class JwksController {

    private final JWKSet jwkSet;

    public JwksController(JWKSet jwkSet) {
        this.jwkSet = jwkSet;
    }

    /**
     * Served at {@code /.well-known/jwks.json} because that is where tooling looks
     * by convention, rather than under {@code /auth} where a reader would expect
     * only user-facing flows.
     *
     * <p>Returned as a {@code Map} rather than the {@code JWKSet} object so Jackson
     * renders the standard JWKS document structure, instead of whatever a library's
     * {@code toString()} happens to produce.
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok(jwkSet.toJSONObject());
    }
}