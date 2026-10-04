package com.example.authserver.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * auth-server is both the issuer and a resource server: it hands out tokens, and it
 * protects its own user endpoints with the tokens it handed out.
 *
 * <p>The decoder is built from the keypair already in memory rather than from
 * {@code jwkSetUri}. A self-call would mean an HTTP request to itself on a cold
 * cache, and it would fail to start if the service had not finished binding its port
 * — the service would be unable to boot unless it could already reach itself. The
 * in-process key also keeps key rotation a restart-time decision, which for now is
 * correct: a new keypair invalidates every existing token, and that should be
 * deliberate.
 *
 * <p>Other services must do the opposite and fetch {@code /.well-known/jwks.json}
 * over HTTP, because they hold no copy of the private key. That asymmetry is the
 * point of the asymmetric signature.
 */
@Configuration
public class ResourceServerConfig {

    /** Endpoints that must work before a caller has a token. */
    private static final String[] PUBLIC_PATHS = {
            "/auth/login",
            "/auth/register",
            "/.well-known/jwks.json",
            "/actuator/**"
    };

    @Bean
    JwtDecoder jwtDecoder(JwtKeyProvider keys,
                          TokenRevocationValidator revocation,
                          @Value("${app.security.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build();

        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains("wallet-service"));

        // Order matters only for the message a caller sees: standard first, so an
        // expired token is reported as expired rather than as revoked.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(standard, audience, revocation));
        return decoder;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                // No session and no cookies: every request carries its own bearer
                // token, so there is nothing for the server to remember between calls.
                // Stateful session auth would let a deactivated user keep working
                // until the session expired, which is precisely the staleness
                // token_version exists to remove.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}