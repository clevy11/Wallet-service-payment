package com.example.walletservice.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in auth-server, so the cross-service tests exercise the real decoder.
 *
 * <p>The obvious shortcut is to mock the {@code JwtDecoder} and hand the test
 * whatever token it wants to be valid. That would test nothing: the properties under
 * test here are exactly the ones a mock replaces — signature verification, algorithm
 * pinning, issuer and audience matching. So this serves a real JWKS over HTTP from a
 * JDK {@link HttpServer} and signs real tokens with a real keypair. Nothing about the
 * production {@code ResourceServerConfig} is stubbed; it fetches the document over the
 * network, from a real socket, and caches it.
 *
 * <p>Deliberately not a Spring bean. It is static because {@code @DynamicPropertySource}
 * runs before the application context exists, and the decoder resolves its key during
 * startup. A bean would be created too late to influence the property.
 *
 * <p>The one thing this cannot prove is that the real auth-server's JWKS endpoint
 * serves a document this decoder accepts — see the note on
 * {@code MyWalletControllerTests}.
 */
public final class JwtTestKeys {

    private static final KeyPair KEY_PAIR = generate();
    private static final KeyPair IMPOSTOR = generate();
    private static final String AUDIENCE = "wallet-service";

    /** What the decoder will accept as {@code iss}, and where it fetches the key. */
    public static final String ISSUER;

    private static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ISSUER = "http://127.0.0.1:" + SERVER.getAddress().getPort();

            SERVER.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = publicJwks().toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            // A tiny fixed pool: the JDK default is an unbounded cached thread pool,
            // which would leave threads behind after every test class.
            SERVER.setExecutor(Executors.newFixedThreadPool(2));
            SERVER.start();

            // Stopped at JVM exit, and deliberately not from any test class.
            //
            // The server is static, so every class in the module shares it, and JUnit
            // runs those classes sequentially: whichever class ran first would stop the
            // server in its @AfterAll, and every class after it would fail with
            // connection refused against a JWKS endpoint that no longer existed --
            // an error pointing at the token decoder rather than at the lifecycle.
            // Reference counting does not help either, for the same reason: a later
            // class's @BeforeAll runs after the earlier class has already released.
            //
            // A shutdown hook is the only lifecycle that is not tied to test order.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> SERVER.stop(0)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the test JWKS server", e);
        }
    }

    /**
     * No longer called by test classes -- see the shutdown hook in the initialiser.
     * Kept because "stop the shared server" is still a legitimate thing to want when
     * driving this class from a main().
     */
    public static void stop() {
        SERVER.stop(0);
    }

    private JwtTestKeys() {
    }

    /** A valid token for this audience and issuer, signed by the trusted key. */
    public static String tokenFor(UUID subject, String roles) {
        return tokenFor(subject, roles, AUDIENCE);
    }

    public static String tokenFor(UUID subject, String roles, String audience) {
        return sign(KEY_PAIR, claims(subject, roles, audience, ISSUER));
    }

    /** Correctly signed, but claiming to come from somewhere else. */
    public static String tokenFromAnotherIssuer(UUID subject) {
        return sign(KEY_PAIR, claims(subject, "USER", AUDIENCE, "http://someone-else"));
    }

    /**
     * Every claim correct and signed by a key this service has never heard of. Only
     * the signature is wrong.
     */
    public static String tokenSignedByAnImpostor(UUID subject) {
        return sign(IMPOSTOR, claims(subject, "USER", AUDIENCE, ISSUER));
    }

    /**
     * The algorithm-confusion attack, assembled for real.
     *
     * <p>The attacker needs a signature that verifies under material the verifier
     * trusts and that the attacker can also read. The published RSA public key is
     * exactly that, so using its bytes as an HMAC secret produces a token that would
     * pass any decoder willing to honour the {@code alg} header. This one must refuse,
     * because the decoder was pinned to RS256.
     */
    public static String hmacTokenUsingThePublicKeyAsASecret(UUID subject) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    claims(subject, "USER", AUDIENCE, ISSUER));
            jwt.sign(new MACSigner(KEY_PAIR.getPublic().getEncoded()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(KeyPair keyPair, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId()).build(),
                    claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet claims(UUID subject, String roles, String audience, String issuer) {
        return new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .issuer(issuer)
                .audience(audience)
                .claim("roles", roles)
                .claim("token_version", 0)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .jwtID(UUID.randomUUID().toString())
                .build();
    }

    private static JWKSet publicJwks() {
        return new JWKSet(new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                .keyID(keyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build());
    }

    /** RFC 7638 thumbprint, the same derivation auth-server uses. */
    private static String keyId() {
        try {
            return new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                    .keyIDFromThumbprint().build().getKeyID();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}