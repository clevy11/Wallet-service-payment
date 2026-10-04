package com.example.authserver.security;

import com.example.authserver.domain.User;
import com.example.authserver.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies a password and issues a token.
 *
 * <p>Separate bean from the controller so {@code @Transactional} actually applies —
 * Spring's proxy is bypassed by a self-invocation, so the same method on the
 * controller class would silently run without a transaction.
 */
@Service
public class LoginService {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenIssuer tokens;

    public LoginService(UserRepository users, PasswordEncoder passwords, TokenIssuer tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public TokenIssuer.IssuedToken login(String username, String password) {
        User user = users.findByUsernameIgnoreCase(username)
                .filter(User::isEnabled)
                .orElse(null);

        // Verify against a dummy hash when the user does not exist, so a missing
        // username and a wrong password cost the same time.
        //
        // Without this, "no such user" returns in microseconds and "wrong password"
        // takes a full BCrypt round — hundreds of milliseconds. That gap is a
        // username oracle: an attacker enumerates valid accounts by timing alone,
        // without ever guessing a password.
        String hash = user != null
                ? user.getPasswordHash()
                : passwords.encode(DUMMY_PASSWORD);

        boolean passwordMatches = passwords.matches(password, hash);
        if (user == null || !passwordMatches) {
            // One message for both causes. "Unknown username" vs "wrong password"
            // tells an attacker which accounts exist.
            throw new BadCredentialsException();
        }

        return tokens.issue(user);
    }

    /**
     * Any string works as the dummy input: it is encoded and then compared against,
     * never accepted. A fixed value keeps the timing constant.
     */
    private static final String DUMMY_PASSWORD = "no-such-user-timing-equaliser";

    /**
     * Deliberately not Spring Security's {@code BadCredentialsException}: this is an
     * auth-server rule, and coupling the domain to a framework type would leak it
     * into every caller. The controller maps it to 401.
     */
    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException() {
            super("invalid username or password");
        }
    }
}