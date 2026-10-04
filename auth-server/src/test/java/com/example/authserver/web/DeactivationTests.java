package com.example.authserver.web;

import java.util.UUID;

import com.example.authserver.domain.User;
import com.example.authserver.support.PostgresIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The end-to-end proof that deactivation means something: a token minted before the
 * account was disabled must stop working afterwards, immediately, without waiting for
 * the 15-minute expiry.
 *
 * <p>Full context with a real Postgres, because the whole point of the feature is a
 * claim compared against a column, and mocking either side would leave nothing under
 * test. Each test signs its token through the real encoder and sends it as a real
 * bearer header, so the filter chain, the decoder and the validator all run.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeactivationTests extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private com.example.authserver.security.TokenIssuer tokens;

    @Test
    @DisplayName("a valid token reaches the account behind it")
    void validTokenIsAccepted() throws Exception {
        String token = tokenFor(anEnabledUser());

        mvc.perform(get("/auth/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ada"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.tokenVersion").value(0));
    }

    @Test
    @DisplayName("no token at all is rejected")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/auth/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token with a forged body is rejected")
    void forgedTokenIsRejected() throws Exception {
        String token = tokenFor(anEnabledUser());

        mvc.perform(get("/auth/users/me").header("Authorization", bearer(token + "x")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The core assertion. The token is legitimate, unexpired and correctly signed;
     * the only thing that changed is a row in authdb. If this passes, deactivation is
     * real rather than best-effort.
     */
    @Test
    @DisplayName("deactivation kills a token that was valid a moment earlier")
    void deactivationRevokesAnExistingToken() throws Exception {
        User user = anEnabledUser();
        String token = tokenFor(user);

        // Precondition: it works before. Without this the test would also pass if the
        // endpoint were simply rejecting everything.
        mvc.perform(get("/auth/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/users/me/deactivate").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.tokenVersion").value(1));

        mvc.perform(get("/auth/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Both halves of deactivation, asserted on the row. A change to {@code enabled}
     * alone would still let a stolen token work for its remaining lifetime, and a bump
     * to {@code token_version} alone would let the user straight back in.
     */
    @Test
    @DisplayName("deactivation disables the account and revokes its tokens together")
    void deactivationWritesBothChanges() throws Exception {
        User user = anEnabledUser();
        String token = tokenFor(user);

        mvc.perform(post("/auth/users/me/deactivate").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.getTokenVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("a disabled user cannot log in again")
    void deactivatedUserCannotLogIn() throws Exception {
        User user = anEnabledUser();
        mvc.perform(post("/auth/users/me/deactivate").header("Authorization", bearer(tokenFor(user))))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ada\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The caller cannot retry. Deactivation revokes the very token the retry would be
     * sent with, so a second attempt is rejected before it reaches the service.
     *
     * <p>Asserted because it is surprising rather than obvious, and because it is the
     * reason a client must treat the response as final: the request may have succeeded
     * even if the response was lost, and re-sending it proves nothing. Idempotency at
     * this endpoint is not achievable, and pretending otherwise is a trap.
     */
    @Test
    @DisplayName("the caller's own token dies with the deactivation, so it cannot retry")
    void theCallersOwnTokenCannotRetry() throws Exception {
        User user = anEnabledUser();
        String token = tokenFor(user);

        mvc.perform(post("/auth/users/me/deactivate").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/users/me/deactivate").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    private User anEnabledUser() {
        User user = new User(UUID.randomUUID(), "ada", "ada@example.com", passwords.encode(PASSWORD));
        users.save(user);
        return user;
    }

    /** Signs a real token for this user, exactly as the login endpoint does. */
    private String tokenFor(User user) {
        return tokens.issue(user).value();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}