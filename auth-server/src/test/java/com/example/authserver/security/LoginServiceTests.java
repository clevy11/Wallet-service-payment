package com.example.authserver.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.example.authserver.domain.User;
import com.example.authserver.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Credentials handling, with a real BCrypt encoder so the hashing is genuinely
 * exercised. Only the repository is mocked: the interesting logic here is the
 * comparison, not the SELECT.
 */
class LoginServiceTests {

    private static final Duration TTL = Duration.ofMinutes(15);

    private UserRepository users;
    private PasswordEncoder passwords;
    private LoginService logins;
    private JwtKeyProvider keys;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        users = mock(UserRepository.class);
        passwords = new BCryptPasswordEncoder();
        keys = new JwtKeyProvider(dir.toString());
        JwtEncoder encoder = NimbusJwtEncoder.withKeyPair(keys.publicKey(), keys.privateKey())
                .algorithm(SignatureAlgorithm.RS256)
                .jwkPostProcessor(jwk -> jwk.keyID(keys.keyId()))
                .build();
        logins = new LoginService(users, passwords, new TokenIssuer(encoder, keys, "http://localhost:8090", TTL));
    }

    @Test
    @DisplayName("the right password yields a token for that user")
    void correctPasswordIssuesAToken() {
        User stored = aStoredUser("correct horse battery staple");
        when(users.findByUsernameIgnoreCase("ada")).thenReturn(Optional.of(stored));

        TokenIssuer.IssuedToken issued = logins.login("ada", "correct horse battery staple");

        assertThat(NimbusJwtDecoder.withPublicKey(keys.publicKey()).build().decode(issued.value()).getSubject())
                .isEqualTo(stored.getId().toString());
    }

    @Test
    @DisplayName("a wrong password is refused")
    void wrongPasswordIsRefused() {
        when(users.findByUsernameIgnoreCase("ada")).thenReturn(Optional.of(aStoredUser("correct horse battery staple")));

        assertThatThrownBy(() -> logins.login("ada", "guess"))
                .isInstanceOf(LoginService.BadCredentialsException.class);
    }

    @Test
    @DisplayName("an unknown user is refused")
    void unknownUserIsRefused() {
        when(users.findByUsernameIgnoreCase("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> logins.login("nobody", "anything"))
                .isInstanceOf(LoginService.BadCredentialsException.class);
    }

    /**
     * A disabled account must not receive a token even with the right password —
     * disabling is the lever for "this person is not allowed in any more", so honouring
     * the password while ignoring the flag would make it useless.
     */
    @Test
    @DisplayName("a disabled user is refused despite a correct password")
    void disabledUserIsRefused() {
        User disabled = aStoredUser("correct horse battery staple");
        disabled.disable();
        when(users.findByUsernameIgnoreCase("ada")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> logins.login("ada", "correct horse battery staple"))
                .isInstanceOf(LoginService.BadCredentialsException.class);
    }

    /**
     * The username oracle. A wrong password and an unknown user must produce an
     * identical message, so a response cannot be used to enumerate accounts.
     *
     * <p>Asserting the messages are equal, rather than asserting the two calls take
     * the same time, is deliberate: timing is the real property but is hopelessly
     * flaky to test, whereas message equality catches the mistake that actually
     * happens in practice — someone writing "no such user".
     */
    @Test
    @DisplayName("the failure message does not reveal which accounts exist")
    void failureMessageDoesNotRevealAccountExistence() {
        when(users.findByUsernameIgnoreCase("nobody")).thenReturn(Optional.empty());
        when(users.findByUsernameIgnoreCase("ada")).thenReturn(Optional.of(aStoredUser("correct horse battery staple")));

        LoginService.BadCredentialsException unknown =
                catchThrowableOfType(() -> logins.login("nobody", "guess"), LoginService.BadCredentialsException.class);
        LoginService.BadCredentialsException wrong =
                catchThrowableOfType(() -> logins.login("ada", "guess"), LoginService.BadCredentialsException.class);

        assertThat(unknown).hasMessage(wrong.getMessage());
    }

    /**
     * The other half of the same defence: without a dummy comparison, "no such user"
     * returns immediately while "wrong password" costs a full BCrypt round. That gap
     * re-opens the oracle by timing, even when the messages match.
     *
     * <p>Verified by call rather than by clock, so the test is deterministic: the
     * unknown-user path must still perform a password comparison.
     */
    @Test
    @DisplayName("an unknown user still costs a password comparison")
    void unknownUserStillCompares(@TempDir Path dir) {
        PasswordEncoder spied = spy(new BCryptPasswordEncoder());
        JwtKeyProvider otherKeys = new JwtKeyProvider(dir.toString());
        LoginService service = new LoginService(users, spied,
                new TokenIssuer(encoderFor(otherKeys), otherKeys, "http://localhost:8090", TTL));
        when(users.findByUsernameIgnoreCase("nobody")).thenReturn(Optional.empty());

        catchThrowableOfType(() -> service.login("nobody", "guess"), LoginService.BadCredentialsException.class);

        verify(spied).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("the username is looked up case-insensitively")
    void lookupIsCaseInsensitive() {
        when(users.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());

        catchThrowableOfType(() -> logins.login("ADA", "guess"), LoginService.BadCredentialsException.class);

        verify(users).findByUsernameIgnoreCase("ADA");
    }

    private User aStoredUser(String password) {
        return new User(UUID.randomUUID(), "ada", "ada@example.com", passwords.encode(password));
    }

    private static JwtEncoder encoderFor(JwtKeyProvider keyProvider) {
        return NimbusJwtEncoder.withKeyPair(keyProvider.publicKey(), keyProvider.privateKey())
                .algorithm(SignatureAlgorithm.RS256)
                .jwkPostProcessor(jwk -> jwk.keyID(keyProvider.keyId()))
                .build();
    }
}