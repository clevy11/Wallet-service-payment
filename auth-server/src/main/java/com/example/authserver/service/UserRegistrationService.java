package com.example.authserver.service;

import java.util.UUID;

import com.example.authserver.events.UserCreatedPayload;

/**
 * Writes a new user and the event announcing them, atomically.
 *
 * <p>Exists as its own bean specifically so {@code @Transactional} applies. Spring
 * implements that annotation with a proxy around the bean, which means a
 * self-invocation inside the same class bypasses it and the method runs with
 * <em>no transaction at all</em> -- silently. Keeping the transactional boundary
 * on a separate public method of a separate bean is the reliable way to avoid
 * that class of bug.
 */
public interface UserRegistrationService {

    RegistrationResult register(String username, String email, String password, UUID correlationId);

    boolean usernameTaken(String username);

    /**
     * @param correlationId trace id taken from the request, stored on the event row
     * @param outboxEventId the id that becomes the published event's {@code eventId}
     */
    record RegistrationResult(UUID userId, String username, UUID outboxEventId, UUID correlationId) {
    }

    /** Exposed so callers can build the payload without duplicating the field list. */
    static UserCreatedPayload payloadFor(UUID userId, String username, String email) {
        return new UserCreatedPayload(userId.toString(), username, email);
    }
}