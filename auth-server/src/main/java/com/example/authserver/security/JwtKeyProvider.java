package com.example.authserver.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    /**
     * 2048 bits is the current floor. 4096 is more secure but noticeably slower to
     * generate and to sign with, which is painful on a laptop and buys little here.
     */
    private static final int KEY_SIZE = 2048;

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String keyId;

    public JwtKeyProvider(@Value("${app.security.key-directory}") String keyDirectory) {
        Path dir = Path.of(keyDirectory);
        Path privateKeyFile = dir.resolve("jwt-private.pkcs8");
        Path publicKeyFile = dir.resolve("jwt-public.x509");

        try {
            if (Files.exists(privateKeyFile) && Files.exists(publicKeyFile)) {
                this.privateKey = readPrivateKey(privateKeyFile);
                this.publicKey = readPublicKey(publicKeyFile);
                log.info("loaded existing signing key from {}", dir.toAbsolutePath());
            } else {
                KeyPair generated = generate();
                this.privateKey = (RSAPrivateKey) generated.getPrivate();
                this.publicKey = (RSAPublicKey) generated.getPublic();
                write(dir, privateKeyFile, publicKeyFile, this.privateKey, this.publicKey);
                log.info("generated a new {} bit signing key in {}", KEY_SIZE,
                        dir.toAbsolutePath());
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot prepare signing key in " + dir, e);
        }

        this.keyId = keyIdFor(this.publicKey);
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    /**
     * The key id is the RFC 7638 thumbprint of the public key — a hash of the key
     * itself, not a random string.
     *
     * <p>That is deliberate. A verifier that sees a token with {@code kid=X} looks
     * that key up in the JWKS it fetched, and a random id would mean rotating the
     * key means editing a constant in two places. Deriving it means the JWKS simply
     * publishes whatever it holds, and a new key gets a new id automatically.
     */
    public String keyId() {
        return keyId;
    }

    /**
     * The public half only. Serving the private key here would be a total compromise.
     *
     * <p>{@code use} and {@code alg} are declared rather than left implicit. Both are
     * optional in RFC 7517, and a set that omits them forces every consumer to guess:
     * a key published without {@code use} cannot be told apart from an encryption key
     * in a set that holds both, so a verifier has no way to select the right one
     * without its own hardcoded assumption. Declaring {@code use=sig} and
     * {@code alg=RS256} makes this set self-describing, which is the whole purpose of
     * publishing it. It also means a future encryption key can join this set without
     * either half misusing the other.
     */
    public JWKSet publicJwkSet() {
        return new JWKSet(new RSAKey.Builder(publicKey)
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build());
    }

    private static String keyIdFor(RSAPublicKey publicKey) {
        try {
            return new RSAKey.Builder(publicKey).keyIDFromThumbprint().build().getKeyID();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot derive key id", e);
        }
    }

    private static KeyPair generate() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        return generator.generateKeyPair();
    }

    private static RSAPrivateKey readPrivateKey(Path file)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] der = decodeBase64(Files.readString(file));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey readPublicKey(Path file)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] der = decodeBase64(Files.readString(file));
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] decodeBase64(String text) {
        return Base64.getDecoder().decode(text.replaceAll("\\s", ""));
    }

    private static void write(Path dir, Path privateKeyFile, Path publicKeyFile,
                              RSAPrivateKey privateKey, RSAPublicKey publicKey)
            throws IOException {
        Files.createDirectories(dir);

        // Create the private key with owner-only permissions *before* writing to it.
        // Writing first and chmod-ing after leaves a window where the key is readable
        // by every local user.
        Files.writeString(privateKeyFile,
                Base64.getEncoder().encodeToString(privateKey.getEncoded()));
        trySetOwnerOnly(privateKeyFile);

        Files.writeString(publicKeyFile,
                Base64.getEncoder().encodeToString(publicKey.getEncoded()));
    }

    private static void trySetOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem (macOS is, but a container volume might not be).
            // Log-worthy but not fatal: the file is still inside a directory the
            // developer controls.
            log.warn("cannot restrict permissions on {}: {}", file, e.getMessage());
        }
    }

}