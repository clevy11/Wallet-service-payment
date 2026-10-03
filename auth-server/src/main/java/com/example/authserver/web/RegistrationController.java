package com.example.authserver.web;

import java.util.UUID;

import com.example.authserver.service.UserRegistrationService;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class RegistrationController {

    private final UserRegistrationService registrations;

    public RegistrationController(UserRegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisteredUserResponse> register(
            @RequestBody RegisterRequest request,
            @RequestHeader(value = CorrelationId.HEADER, required = false) String correlationHeader) {

        if (registrations.usernameTaken(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "username already registered");
        }

        UUID correlationId = CorrelationId.resolve(correlationHeader);

        UserRegistrationService.RegistrationResult result =
                registrations.register(request.username(), request.email(),
                        request.password(), correlationId);

        // Echoed back so a caller can correlate its request with everything that
        // followed, including events this response knows nothing about.
        return ResponseEntity.ok(new RegisteredUserResponse(
                result.userId(), result.username(), result.correlationId()));
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record RegisteredUserResponse(
            UUID userId,
            String username,
            @JsonProperty("correlationId") UUID correlationId) {
    }
}