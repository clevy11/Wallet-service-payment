package com.example.walletservice.web;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.walletservice.domain.Wallet;
import com.example.walletservice.support.JwtTestKeys;
import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract: status codes, headers, and what the domain decisions look like
 * from outside.
 *
 * <p>The status-code assertions are the point of this class rather than an
 * afterthought. {@code WalletExceptionHandler} exists because a rejected transfer
 * arriving as a 500 tells the caller to retry a request that can never succeed, and
 * that behaviour is invisible from the service tests -- they stop at the exception.
 * {@code 409} versus {@code 500} is decided here and nowhere else.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WalletControllerTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void issuerLocation(DynamicPropertyRegistry registry) {
        registry.add("app.security.auth-server-issuer", () -> JwtTestKeys.ISSUER);
    }

    @Test
    @DisplayName("opening a wallet returns 201 the first time and 200 on a retry")
    void createIsIdempotentPerCurrency() throws Exception {
        UUID owner = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), owner, "Ada Lovelace");
        String token = JwtTestKeys.tokenFor(owner, "USER");

        mvc.perform(post("/wallets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("USD"))
                // A string, so a client parsing it as a JSON number cannot lose the
                // fourth decimal place to a double.
                .andExpect(jsonPath("$.balance").value("0.0000"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(post("/wallets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a bad currency is 400, not a constraint violation")
    void badCurrencyIsABadRequest() throws Exception {
        UUID owner = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), owner, "Ada Lovelace");

        mvc.perform(post("/wallets")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(owner, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"DOLLARS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid currency"));
    }

    @Test
    @DisplayName("a transfer moves money and returns 201 with the transfer id")
    void transferSucceeds() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payee = payeeWith("0.00");
        UUID source = walletOf(payer);
        UUID destination = walletOf(payee);

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"30.00"}
                                """.formatted(source, destination)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").isNotEmpty())
                .andExpect(jsonPath("$.amount").value("30.0000"))
                .andExpect(jsonPath("$.sourceBalance").value("70.0000"))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    /**
     * The replay answers 200 with {@code replayed: true} rather than 201.
     *
     * <p>Both halves matter. 201 would tell the client it had just created a second
     * transfer, which it will then try to reconcile; and the money is only debited
     * once, so a caller that trusted the 201 would be wrong about its own balance.
     */
    @Test
    @DisplayName("a retried transfer returns 200 and the original transfer id")
    void retryIsAReplay() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payee = payeeWith("0.00");
        UUID source = walletOf(payer);
        UUID destination = walletOf(payee);
        String body = """
                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"30.00"}
                """.formatted(source, destination);

        String first = mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String transferId = objectMapper.readTree(first).get("transferId").asText();

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.transferId").value(transferId));

        assertThat(wallets.findById(source).orElseThrow().getBalance())
                .isEqualByComparingTo("70.00");
        assertThat(entries.findAll()).hasSize(2);
    }

    /**
     * 409, and specifically not 500.
     *
     * <p>A 500 tells the client this is the server's fault and that retrying may help,
     * which is wrong on both counts: the request is well-formed, and retrying it will
     * fail identically until the account is funded.
     */
    @Test
    @DisplayName("insufficient funds is 409 with a machine-readable reason")
    void insufficientFundsIsAConflict() throws Exception {
        UUID payer = payerWith("10.00");
        UUID payee = payeeWith("0.00");

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"99.00"}
                                """.formatted(walletOf(payer), walletOf(payee))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_FUNDS"));
    }

    /** 422: well-formed, but the operation is undefined without an FX rate. */
    @Test
    @DisplayName("cross-currency is 422, not 409")
    void currencyMismatchIsUnprocessable() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payeeOwner = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), payeeOwner, "Payee");
        UUID destination = UUID.randomUUID();
        wallets.saveAndFlush(new Wallet(destination,
                customers.findByOwnerId(payeeOwner).orElseThrow().getId(), "EUR",
                new BigDecimal("0.00")));

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"10.00"}
                                """.formatted(walletOf(payer), destination)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("CURRENCY_MISMATCH"));
    }

    /**
     * 404 for a wallet that is not the caller's, exactly as for one that does not
     * exist. The two are deliberately indistinguishable so the response cannot be used
     * to discover which wallet ids are real.
     */
    @Test
    @DisplayName("somebody else's wallet is 404, not 403")
    void anotherCustomersWalletIsNotFound() throws Exception {
        UUID payer = payerWith("100.00");
        UUID victimOwner = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), victimOwner, "Victim");
        UUID victim = wallets.saveAndFlush(new Wallet(UUID.randomUUID(),
                customers.findByOwnerId(victimOwner).orElseThrow().getId(), "USD",
                new BigDecimal("500.00"))).getId();
        // The destination is an unrelated payee's wallet. The payer already has one
        // USD wallet of their own -- one per currency is the rule -- so the "other"
        // wallet here has to belong to somebody else.
        UUID payee = payeeWith("0.00");

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-005")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"500.00"}
                                """.formatted(victim, walletOf(payee))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_WALLET"));

        assertThat(wallets.findById(victim).orElseThrow().getBalance())
                .isEqualByComparingTo("500.00");
    }

    /**
     * More precision than the column can hold is rejected, not rounded.
     *
     * <p>Postgres would round 10.00001 to 10.0000 and say nothing, so the payee would
     * receive a different amount than was requested, with no trace in the response.
     */
    @Test
    @DisplayName("an amount with more than 4 decimal places is 400")
    void overPreciseAmountIsABadRequest() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payee = payeeWith("0.00");

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-009")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"10.00001"}
                                """.formatted(walletOf(payer), walletOf(payee))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_AMOUNT"));

        assertThat(entries.findAll()).isEmpty();
    }

    /** A negative or zero amount never reaches the database. */
    @Test
    @DisplayName("a non-positive amount is 400")
    void nonPositiveAmountIsABadRequest() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payee = payeeWith("0.00");

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-006")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"-5.00"}
                                """.formatted(walletOf(payer), walletOf(payee))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_AMOUNT"));
    }

    /**
     * The header is mandatory on this endpoint.
     *
     * <p>Without a key there is no way to tell a retry from a second, genuine payment
     * of the same amount -- so the request is refused rather than accepted with a
     * best-effort guard.
     */
    @Test
    @DisplayName("a transfer with no Idempotency-Key is 400")
    void missingIdempotencyKeyIsRejected() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payee = payeeWith("0.00");

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"1.00"}
                                """.formatted(walletOf(payer), walletOf(payee))))
                .andExpect(status().isBadRequest());

        assertThat(entries.findAll()).isEmpty();
    }

    /** An inbound correlation id is echoed, so a caller can find its own trace. */
    @Test
    @DisplayName("an X-Correlation-Id is accepted and echoed back")
    void correlationIdIsPropagated() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payee = payeeWith("0.00");
        String correlationId = UUID.randomUUID().toString();

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-007")
                        .header(CorrelationId.HEADER, correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"5.00"}
                                """.formatted(walletOf(payer), walletOf(payee))))
                .andExpect(status().isCreated());

        // Captured at the edge and stored on the event, not minted at publish time.
        assertThat(outbox.findAll()).singleElement()
                .extracting(event -> event.getCorrelationId().toString())
                .isEqualTo(correlationId);
    }

    /** A malformed tracing header must not fail the request. */
    @Test
    @DisplayName("a malformed correlation id is replaced, not rejected")
    void malformedCorrelationIdDoesNotFailTheRequest() throws Exception {
        UUID payer = payerWith("100.00");
        UUID payee = payeeWith("0.00");

        mvc.perform(post("/transfers")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(payer, "USER"))
                        .header("Idempotency-Key", "inv-008")
                        .header(CorrelationId.HEADER, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceWalletId":"%s","destinationWalletId":"%s","amount":"5.00"}
                                """.formatted(walletOf(payer), walletOf(payee))))
                .andExpect(status().isCreated());

        assertThat(outbox.findAll()).singleElement()
                .satisfies(event -> assertThat(event.getCorrelationId()).isNotNull());
    }

    @Test
    @DisplayName("history is refused for a wallet the caller does not own")
    void historyIsScopedToTheCaller() throws Exception {
        UUID mine = payerWith("100.00");
        UUID theirsOwner = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), theirsOwner, "Other");
        UUID theirs = wallets.saveAndFlush(new Wallet(UUID.randomUUID(),
                customers.findByOwnerId(theirsOwner).orElseThrow().getId(), "USD",
                new BigDecimal("10.00"))).getId();

        mvc.perform(get("/wallets/" + theirs + "/entries")
                        .header("Authorization", "Bearer " + JwtTestKeys.tokenFor(mine, "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("no token is rejected on the new endpoints too")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/wallets")).andExpect(status().isUnauthorized());
        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}"))
                .andExpect(status().isUnauthorized());
    }

    private UUID payerWith(String opening) {
        UUID owner = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), owner, "Payer");
        UUID wallet = UUID.randomUUID();
        wallets.saveAndFlush(new Wallet(wallet,
                customers.findByOwnerId(owner).orElseThrow().getId(), "USD",
                new BigDecimal(opening)));
        return owner;
    }

    private UUID payeeWith(String opening) {
        UUID owner = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), owner, "Payee");
        UUID wallet = UUID.randomUUID();
        wallets.saveAndFlush(new Wallet(wallet,
                customers.findByOwnerId(owner).orElseThrow().getId(), "USD",
                new BigDecimal(opening)));
        return owner;
    }

    /** The payer's own wallet -- transfers always start from one. */
    private UUID walletOf(UUID owner) {
        return wallets.findByCustomerId(customers.findByOwnerId(owner).orElseThrow().getId())
                .get(0).getId();
    }
}
