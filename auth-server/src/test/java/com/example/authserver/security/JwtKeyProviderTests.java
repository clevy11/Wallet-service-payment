package com.example.authserver.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key management, tested without Spring, a database or a broker: this class needs
 * nothing but a directory, and it is the one piece of the auth path whose failure is
 * silent and total.
 */
class JwtKeyProviderTests {

    @Test
    @DisplayName("generates a keypair on first use and writes it to disk")
    void generatesOnFirstUse(@TempDir Path dir) {
        JwtKeyProvider keys = new JwtKeyProvider(dir.toString());

        assertThat(keys.publicKey()).isNotNull();
        assertThat(keys.privateKey()).isNotNull();
        assertThat(Files.exists(dir.resolve("jwt-private.pkcs8"))).isTrue();
        assertThat(Files.exists(dir.resolve("jwt-public.x509"))).isTrue();
    }

    /**
     * The reason keys are persisted at all. A fresh keypair per boot would invalidate
     * every token already in flight, so every deploy would silently log out every
     * user — with no error anywhere, just 401s appearing after a restart.
     */
    @Test
    @DisplayName("reuses the existing key rather than generating a new one")
    void reusesTheExistingKey(@TempDir Path dir) {
        JwtKeyProvider first = new JwtKeyProvider(dir.toString());
        JwtKeyProvider second = new JwtKeyProvider(dir.toString());

        assertThat(second.publicKey()).isEqualTo(first.publicKey());
        assertThat(second.keyId()).isEqualTo(first.keyId());
    }

    @Test
    @DisplayName("the key id is derived from the key, so a new key gets a new id")
    void keyIdTracksTheKey(@TempDir Path dir) throws Exception {
        JwtKeyProvider keys = new JwtKeyProvider(dir.toString());

        // Derived from an RFC 7638 thumbprint, not random: a verifier looks the
        // signing key up by kid, and a random id would need changing in two places
        // whenever the key rotated.
        assertThat(keys.keyId()).isNotBlank();
        Files.delete(dir.resolve("jwt-private.pkcs8"));
        Files.delete(dir.resolve("jwt-public.x509"));

        JwtKeyProvider rotated = new JwtKeyProvider(dir.toString());
        assertThat(rotated.keyId()).isNotEqualTo(keys.keyId());
    }

    /**
     * The single most important assertion in the class: the published JWKS must carry
     * no private material. A verifier is meant to be able to check a signature and
     * nothing more. If the private exponent ever appeared here, every verifier would
     * also be an issuer.
     */
    @Test
    @DisplayName("the published JWKS contains no private key material")
    void jwksExposesOnlyThePublicKey(@TempDir Path dir) {
        JwtKeyProvider keys = new JwtKeyProvider(dir.toString());

        Map<String, Object> document = keys.publicJwkSet().toJSONObject();
        assertThat(document).containsKey("keys");

        // Asserted structurally, not by substring-matching a rendering. Map.toString()
        // produces {kty=RSA}, which is not what any client ever parses -- and a
        // substring test would pass or fail for reasons unrelated to the security
        // property being checked here.
        @SuppressWarnings("unchecked")
        Map<String, Object> jwk =
                ((List<Map<String, Object>>) document.get("keys")).getFirst();

        assertThat(jwk).containsEntry("kty", "RSA").containsKeys("n", "e");
        // "d" is the private exponent; "p"/"q"/"dp"/"dq"/"qi" the private primes. If
        // any of these appeared, every verifier would also be an issuer.
        assertThat(jwk).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    }

    @Test
    @DisplayName("the published key id is the one the encoder will use")
    void jwksAdvertisesTheActiveKeyId(@TempDir Path dir) {
        JwtKeyProvider keys = new JwtKeyProvider(dir.toString());

        assertThat(keys.publicJwkSet().getKeys())
                .singleElement()
                .satisfies(jwk -> assertThat(jwk.getKeyID()).isEqualTo(keys.keyId()));
    }
}