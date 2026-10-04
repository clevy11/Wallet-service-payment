package com.example.authserver.web;

import com.example.authserver.security.LoginService;
import com.example.authserver.security.TokenIssuer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The login endpoint: credentials in, signed access token out.
 *
 * <p>No JWT is issued anywhere else. Registration deliberately does not log the new
 * user in — a token handed out before the password has been proven is a token the
 * caller did not earn.
 */
@RestController
@RequestMapping("/auth")
public class AuthEndpoints {

    private final LoginService logins;

    public AuthEndpoints(LoginService logins) {
        this.logins = logins;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        try {
            TokenIssuer.IssuedToken token = logins.login(request.username(), request.password());
            return new LoginResponse(token.value(), "Bearer", token.ttl().toSeconds(),
                    token.expiresAt().getEpochSecond());
        } catch (LoginService.BadCredentialsException e) {
            // 401, not 403: the caller has not proven who they are. A 403 claims we
            // know who they are and refuse anyway.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    public record LoginRequest(String username, String password) {
    }

    /**
     * {@code expires_in} is a duration in seconds per RFC 6749, not a timestamp —
     * clients subtract it from their own clock. {@code expires_at} is an absolute
     * epoch second as well, so a client whose clock has drifted can still work out
     * when the token actually dies.
     */
    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            long expiresAt) {
    }
}