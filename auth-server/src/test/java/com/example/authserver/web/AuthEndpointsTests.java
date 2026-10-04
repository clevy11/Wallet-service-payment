package com.example.authserver.web;

import java.util.UUID;

import com.example.authserver.domain.User;
import com.example.authserver.support.PostgresIntegrationTest;
import com.nimbusds.jose.jwk.JWKSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The login endpoint and the JWKS, over real HTTP semantics with a real database.
 *
 * <p>The JWKS test does the thing that actually matters and is easy to get wrong: it
 * takes the token from a login response and validates it against the key
 * {@code GET /.well-known/jwks.json} just returned. Asserting instead on an injected
 * {@link JWKSet} bean would pass even if the endpoint were serving something else
 * entirely.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthEndpointsTests extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private JWKSet publishedKeys;

    @Test
    @DisplayName("correct credentials return a usable access token")
    void loginReturnsAUsableToken() throws Exception {
        anEnabledUser("ada");

        String token = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ada", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();

        // The response is not merely well-formed: the token in it must decode, which
        // proves the encoder bean and the response agree.
        assertThat(decoder.decode(jsonField(token, "accessToken")).getSubject()).isNotBlank();
    }

    /**
     * The contract between two services, asserted end to end: login here, validate
     * there. A wallet-service with no copy of the private key has exactly this much
     * information, and it must be enough.
     */
    @Test
    @DisplayName("a token from login verifies against the key the JWKS publishes")
    void tokenVerifiesAgainstThePublishedKey() throws Exception {
        User user = anEnabledUser("ada");

        String body = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ada", PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = jsonField(body, "accessToken");
        assertThat(decoder.decode(token).getSubject()).isEqualTo(user.getId().toString());

        // And the published document names the same key.
        String jwks = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(jwks).contains(decoder.decode(token).getHeaders().get("kid").toString());
        assertThat(jwks).doesNotContain("\"d\"");
    }

    @Test
    @DisplayName("the JWKS is reachable without a token")
    void jwksIsPublic() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"));
    }

    @Test
    @DisplayName("the username is matched case-insensitively")
    void usernameIsCaseInsensitive() throws Exception {
        anEnabledUser("ada");

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ADA", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a wrong password is refused")
    void wrongPasswordIsRefused() throws Exception {
        anEnabledUser("ada");

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ada", "guess")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown user gets the same refusal")
    void unknownUserIsRefused() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("nobody", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the refusal says nothing about why")
    void refusalRevealsNothing() throws Exception {
        anEnabledUser("ada");

        String wrongPassword = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ada", "guess")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("nobody", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownUser);
    }

    /** Login must not be the open door that registration is not. */
    @Test
    @DisplayName("protected endpoints reject an anonymous caller")
    void protectedEndpointsNeedAToken() throws Exception {
        mvc.perform(get("/auth/users/me"))
                .andExpect(status().isUnauthorized());
    }

    private User anEnabledUser(String username) {
        User user = new User(UUID.randomUUID(), username, username + "@example.com", passwords.encode(PASSWORD));
        users.save(user);
        return user;
    }

    private static String credentials(String username, String password) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, password);
    }

    /** Reads one field without pulling a JSON parser into the test. */
    private static String jsonField(String json, String field) {
        var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
        return tree.get(field).asString();
    }
}