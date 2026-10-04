package com.example.authserver.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.authserver.domain.OutboxEvent;
import com.example.authserver.domain.User;
import com.example.authserver.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The point of the outbox is that two rows become visible together or not at all,
 * so that is what gets asserted: never a user without its announcement, and never
 * an announcement for a user who does not exist.
 */
class UserRegistrationServiceTests extends PostgresIntegrationTest {

    @Autowired
    private UserRegistrationService registrations;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * The regression guard for the placeholder this slice replaced: the stored value
     * must be a real BCrypt hash, never the password itself.
     *
     * <p>Asserted in three ways because a weaker assertion would pass for the wrong
     * reason. "Does not contain the password" is satisfied by a reversible encoding
     * such as Base64, which is worse than plaintext -- it looks like a hash to a
     * reviewer while still yielding the password instantly. Pinning the {@code $2a$}
     * prefix instead confirms the algorithm actually used.
     */
    @Test
    void storesABcryptHashRatherThanThePassword() {
        UUID correlationId = UUID.randomUUID();

        UserRegistrationService.RegistrationResult result =
                registrations.register("ada", "ada@example.com", "correct horse", correlationId);

        String hash = users.findById(result.userId()).orElseThrow().getPasswordHash();
        assertThat(hash)
                .startsWith("$2a$")
                .doesNotContain("correct horse")
                .hasSizeGreaterThanOrEqualTo(59);
        assertThat(passwordEncoder.matches("correct horse", hash)).isTrue();
        assertThat(passwordEncoder.matches("not the password", hash)).isFalse();
    }

    /**
     * Two users with the same password must not share a hash. Without an internal
     * salt, identical passwords produce identical rows, which turns one cracked
     * account into a silent confirmation of every other account that shares the
     * password -- and tells an attacker which accounts are worth attacking together.
     */
    @Test
    void theSamePasswordHashesDifferentlyPerUser() {
        UUID correlationId = UUID.randomUUID();

        registrations.register("ada", "ada@example.com", "correct horse", correlationId);
        registrations.register("grace", "grace@example.com", "correct horse", correlationId);

        List<String> hashes = users.findAll().stream().map(User::getPasswordHash).toList();
        assertThat(hashes).hasSize(2);
        assertThat(hashes.get(0)).isNotEqualTo(hashes.get(1));
    }

    @Test
    void writesTheUserAndTheEventInOneTransaction() {
        UUID correlationId = UUID.randomUUID();

        UserRegistrationService.RegistrationResult result =
                registrations.register("ada", "ada@example.com", "correct horse", correlationId);

        assertThat(users.findById(result.userId())).isPresent();
        assertThat(outbox.findById(result.outboxEventId())).isPresent();

        OutboxEvent event = outbox.findById(result.outboxEventId()).orElseThrow();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getEventType()).isEqualTo("UserCreated");
        assertThat(event.getAggregateType()).isEqualTo("User");
        assertThat(event.getAggregateId()).isEqualTo(result.userId());
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);
        assertThat(event.getAttempts()).isZero();
    }

    /**
     * The correlation id is read from the row, not minted by the relay. This proves
     * the value that reached the database is the caller's -- an id generated at
     * publish time would look identical here but trace nothing.
     */
    @Test
    void storesTheCallersCorrelationIdOnTheEventRow() {
        UUID correlationId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        registrations.register("grace", "grace@example.com", "hopper", correlationId);

        assertThat(outbox.findAll())
                .singleElement()
                .satisfies(event -> assertThat(event.getCorrelationId()).isEqualTo(correlationId));
    }

    @Test
    void storesAPayloadMatchingTheCreatedUser() throws Exception {
        UserRegistrationService.RegistrationResult result =
                registrations.register("alan", "alan@example.com", "turing", UUID.randomUUID());

        OutboxEvent event = outbox.findById(result.outboxEventId()).orElseThrow();
        JsonNode payload = objectMapper.readTree(event.getPayload());

        assertThat(payload.get("userId").asText()).isEqualTo(result.userId().toString());
        assertThat(payload.get("username").asText()).isEqualTo("alan");
        assertThat(payload.get("email").asText()).isEqualTo("alan@example.com");
    }

    /**
     * occurred_at is set when the change happened, so the consumer can tell how stale
     * an event is. A relay-set timestamp would be the publish time and would hide
     * any delay between commit and publication.
     */
    @Test
    void timestampsTheEventAtWriteTimeNotPublishTime() {
        Instant before = Instant.now().minusSeconds(1);

        UserRegistrationService.RegistrationResult result =
                registrations.register("edsger", "e@example.com", "dijkstra", UUID.randomUUID());

        OutboxEvent event = outbox.findById(result.outboxEventId()).orElseThrow();
        assertThat(event.getOccurredAt()).isAfterOrEqualTo(before);
        assertThat(event.getOccurredAt()).isBeforeOrEqualTo(Instant.now());
    }

    /**
     * Registration is the one place a duplicate must be refused: two rows sharing an
     * owner_id leave wallet-service unable to tell which auth user a payment belongs
     * to. The DB unique index is the real guarantee, so this asserts the index rather
     * than the controller's pre-check -- a pre-check alone would still race, since two
     * concurrent registrations both see "free" and both proceed.
     */
    @Test
    void databaseRefusesTwoUsersWithTheSameUsernameIgnoringCase() {
        UUID first = UUID.randomUUID();
        users.saveAndFlush(new User(first, "edsger", "first@example.com", "one"));

        assertThatThrownBy(() -> users.saveAndFlush(
                        new User(UUID.randomUUID(), "EDSGER", "second@example.com", "two")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void usernameTakenIgnoresCase() {
        registrations.register("edsger", "first@example.com", "one", UUID.randomUUID());

        assertThat(registrations.usernameTaken("edsger")).isTrue();
        assertThat(registrations.usernameTaken("EDSGER")).isTrue();
        assertThat(registrations.usernameTaken("nobody")).isFalse();
    }
}