package com.example.walletservice.web;

import java.util.UUID;

import com.example.walletservice.domain.Customer;
import com.example.walletservice.support.JwtTestKeys;
import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cross-service test, and the reason for the whole asymmetric design: a token
 * minted by one service is accepted by another that has never seen the signing key.
 *
 * <p>This class is deliberately unautomated in one direction — it starts no auth-server
 * and calls no real JWKS endpoint. Instead it generates its own throwaway keypair,
 * publishes a JWKS from it, and points this service's decoder at that document via a
 * property. The consequence is worth stating plainly, because it is a real limit of
 * this test: it proves the <em>mechanism</em> (fetch a JWKS, verify with the public
 * key, honour the claims) but not the <em>deployment</em> (that auth-server's actual
 * endpoint serves a document this decoder can consume). Only the manual end-to-end run
 * covers that, and a test that boots two services and a broker to prove it would cost
 * more than it is worth on a laptop that already needs 30-40s per JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyWalletControllerTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /**
     * Points this service's decoder at the stand-in auth-server. Registered as a
     * dynamic property because the JWKS server has to be listening before the context
     * builds its decoder, and a static value in {@code @SpringBootTest} would be fixed
     * before anything had started.
     */
    @DynamicPropertySource
    static void issuerLocation(DynamicPropertyRegistry registry) {
        registry.add("app.security.auth-server-issuer", () -> JwtTestKeys.ISSUER);
    }

    @Test
    @DisplayName("a token from another service resolves the wallet it names")
    void tokenResolvesTheCustomer() throws Exception {
        UUID ownerId = UUID.randomUUID();
        aCustomerOwnedBy(ownerId);

        MvcResult result = mvc.perform(get("/wallets/me")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(ownerId, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.roles").value("USER"))
                .andReturn();

        // The issuer is echoed back so a client can see which authority vouched for
        // the token it presented. The audience is not echoed: it is a check that has
        // already passed by the time this runs, proven by foreignAudienceIsRejected.
        assertThat(result.getResponse().getContentAsString()).contains(JwtTestKeys.ISSUER);
    }

    /**
     * The property the whole design rests on. There is no endpoint parameter that
     * names a customer, so possession of a valid token is the only thing that decides
     * whose wallet comes back.
     */
    @Test
    @DisplayName("one caller cannot read another caller's wallet")
    void aCallerCannotReadAnotherWallet() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        aCustomerOwnedBy(mine);
        aCustomerOwnedBy(theirs);

        mvc.perform(get("/wallets/me")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(mine, "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(mine.toString()))
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"));
    }

    @Test
    @DisplayName("a valid token for an account with no wallet yet is a 404 saying so")
    void noCustomerYetIsNotFound() throws Exception {
        mvc.perform(get("/wallets/me")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(UUID.randomUUID(), "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("no token is rejected")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/wallets/me")).andExpect(status().isUnauthorized());
    }

    /**
     * The confused-deputy check. A token minted for another service must not work
     * here, even though it is signed by a key this service trusts — otherwise any
     * service holding a token for itself could use it against every other service
     * sharing the issuer.
     */
    @Test
    @DisplayName("a token minted for a different audience is rejected")
    void foreignAudienceIsRejected() throws Exception {
        mvc.perform(get("/wallets/me")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(
                                UUID.randomUUID(), "USER", "notification-service")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token from a different issuer is rejected")
    void foreignIssuerIsRejected() throws Exception {
        mvc.perform(get("/wallets/me")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFromAnotherIssuer(
                                UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token signed by an untrusted key is rejected")
    void untrustedSignatureIsRejected() throws Exception {
        // Same claims, same audience, correct issuer: only the signature is wrong.
        String forged = JwtTestKeys.tokenSignedByAnImpostor(UUID.randomUUID());

        mvc.perform(get("/wallets/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The algorithm-confusion attack, attempted for real rather than asserted in the
     * abstract. The attacker takes the <em>public</em> key this service trusts, uses
     * its bytes as an HMAC secret, and signs an HS256 token. The issuer, audience,
     * subject and expiry are all perfectly valid, and the attacker has produced a
     * signature that verifies against material anyone can read.
     *
     * <p>It must be rejected. It is rejected only because the decoder was pinned to
     * RS256 — a decoder that trusted the {@code alg} header would accept this.
     */
    @Test
    @DisplayName("an HS256 token signed with the public key is rejected")
    void algorithmConfusionIsRejected() throws Exception {
        String confused = JwtTestKeys.hmacTokenUsingThePublicKeyAsASecret(UUID.randomUUID());

        mvc.perform(get("/wallets/me").header("Authorization", "Bearer " + confused))
                .andExpect(status().isUnauthorized());
    }

    private void aCustomerOwnedBy(UUID ownerId) {
        customers.createIfAbsent(UUID.randomUUID(), ownerId, "Ada Lovelace");
    }
}