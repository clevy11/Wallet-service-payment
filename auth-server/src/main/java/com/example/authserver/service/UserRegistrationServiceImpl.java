package com.example.authserver.service;

import java.util.UUID;

import com.example.authserver.domain.OutboxEvent;
import com.example.authserver.domain.User;
import com.example.authserver.events.UserCreatedPayload;
import com.example.authserver.repository.OutboxEventRepository;
import com.example.authserver.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class UserRegistrationServiceImpl implements UserRegistrationService {

    private static final String AGGREGATE_TYPE = "User";
    private static final String EVENT_TYPE = "UserCreated";
    private static final int SCHEMA_VERSION = 1;

    private final UserRepository users;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwords;

    public UserRegistrationServiceImpl(UserRepository users, OutboxEventRepository outbox,
                                       ObjectMapper objectMapper, PasswordEncoder passwords) {
        this.users = users;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.passwords = passwords;
    }

    /**
     * The atomic write. Two rows, one transaction: either a user exists and the
     * intent to announce them is durably recorded, or neither happened.
     *
     * <p>Deliberately contains no Kafka call. Sending from here would be the
     * dual-write bug -- a transaction that cannot cover both systems, leaving a
     * window in which the user is committed but the announcement is lost with no
     * trace and no way to detect it.
     */
    @Transactional(readOnly = true)
    @Override
    public boolean usernameTaken(String username) {
        return users.existsByUsernameIgnoreCase(username);
    }

    @Transactional
    @Override
    public RegistrationResult register(String username, String email, String password,
                                       UUID correlationId) {
        UUID userId = UUID.randomUUID();
        // Hashed inside this transaction rather than in the controller: the encoder
        // is deliberately slow, and doing it outside would hold no lock but also
        // guarantee nothing. More importantly the hash must be part of the same
        // atomic write as the event, or a user could exist with no event.
        User user = new User(userId, username, email, passwords.encode(password));

        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(
                eventId,
                AGGREGATE_TYPE,
                userId,
                EVENT_TYPE,
                SCHEMA_VERSION,
                correlationId,
                serialise(UserRegistrationService.payloadFor(userId, username, email)));

        // save() on both, then a single flush: JPA would otherwise defer the
        // INSERTs to commit, and the ordering guarantee this method documents
        // would be an accident of the flush plan rather than something visible.
        users.save(user);
        outbox.save(event);
        outbox.flush();

        return new RegistrationResult(userId, username, eventId, correlationId);
    }

    private String serialise(UserCreatedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // Thrown inside the transaction, so nothing is committed. An event that
            // cannot be serialised must abort the registration rather than create a
            // user nobody will ever hear about.
            throw new IllegalStateException("cannot serialise UserCreated payload", e);
        }
    }
}