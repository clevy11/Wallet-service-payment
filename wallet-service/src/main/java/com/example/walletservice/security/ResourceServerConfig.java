package com.example.walletservice.security;

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
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * wallet-service as a resource server: it trusts tokens because auth-server signed
 * them, and never holds the key that signed them.
 *
 * <p>The asymmetry with auth-server is the point of the whole exercise.
 * {@code TokenSecurityConfig} on the issuer builds its decoder from the private
 * keypair already in memory; this one has no key material at all and must fetch the
 * public half over HTTP from {@code /.well-known/jwks.json}. A stolen wallet-service
 * yields database rows and nothing that lets an attacker mint a token — the theft
 * that matters, being the signing key, happens on exactly one service.
 *
 * <p>The signing key is fetched from the JWKS and cached, so the HTTP call is not on
 * the request path. Two consequences worth being explicit about: wallet-service now
 * cannot start before auth-server is reachable (it resolves the key lazily on first
 * token, but a failed fetch is not retried until the cache expires), and revoking
 * wallet-service's access to the key does not take effect until that cache is
 * refreshed. Both are acceptable for a local key that never rotates.
 *
 * <p>No revocation validator here, deliberately. Rejecting a deactivated user's token
 * the instant it is revoked would mean asking auth-server about every request — a
 * synchronous call per request, adding auth-server's latency to every one of them and
 * making wallet-service unable to serve anyone at all while auth-server is down. The
 * trade taken instead is staleness bounded by the token TTL: up to 15 minutes of
 * access for a user deactivated in that window. The alternative designs, and what
 * each costs, are the subject of the token-revocation slice.
 */
@Configuration
public class ResourceServerConfig {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.security.auth-server-issuer}") String issuer,
            @Value("${app.security.audience}") String audience) {

        // Not JwtDecoders.fromIssuerLocation: that expects OpenID Connect discovery
        // metadata at /.well-known/openid-configuration, which auth-server does not
        // serve. Pointing it at the JWKS directly is the honest dependency -- one
        // document, fetched once, cached.
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(issuer + "/.well-known/jwks.json")
                // The algorithm is pinned rather than read from the token's own header.
                // This closes the algorithm-confusion attack: an attacker can take the
                // published RSA public key, use its bytes as an HMAC secret, and mint
                // an HS256 token. A decoder that trusted the header would verify that
                // signature successfully, because the "secret" is public. Only RS256
                // is ever accepted, so an HS256 token never reaches the MAC check.
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerAndExpiry = JwtValidators.createDefaultWithIssuer(issuer);
        // The issuer minted this token for wallet-service specifically. Without the
        // audience check a token minted for another service is equally acceptable
        // here, which is the confused-deputy problem: any service holding a valid
        // token for itself could present it to any other service that trusts the same
        // issuer.
        OAuth2TokenValidator<Jwt> audienceCheck = new JwtClaimValidator<List<String>>(
                "aud", aud -> aud != null && aud.contains(audience));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndExpiry, audienceCheck));
        return decoder;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Health must stay open: an orchestrator probing readiness is
                        // not going to present a bearer token, and a probe that fails
                        // would take the instance out of rotation.
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}