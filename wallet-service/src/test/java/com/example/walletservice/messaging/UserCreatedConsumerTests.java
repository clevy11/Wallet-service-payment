package com.example.walletservice.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.example.walletservice.events.EventEnvelope;
import com.example.walletservice.events.UserCreatedPayload;
import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The relay promises at-least-once, so a duplicate is normal operation rather than
 * an edge case. These tests call the consumer directly instead of going through a
 * broker: the behaviour under test is what the listener does with an envelope it has
 * been handed, and a real broker would only add a second reason for the test to fail.
 */
class UserCreatedConsumerTests extends PostgresIntegrationTest {

    @Autowired
    private UserCreatedConsumer consumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void provisionsACustomerFromTheEventPayload() {
        UUID userId = UUID.randomUUID();

        consumer.onEvent(userCreated(userId, "ada", "ada@example.com"));

        assertThat(customers.count()).isEqualTo(1);
        assertThat(customers.findByOwnerId(userId)).isPresent()
                .get()
                .extracting(customer -> customer.getDisplayName())
                .isEqualTo("ada");
    }

    /**
     * The duplicate that matters: the same envelope delivered twice. The second call
     * must leave exactly one customer and must not fail -- an outbox that re-sends on
     * every relay restart would otherwise fill the table with copies.
     */
    @Test
    void appliesTheSameEventOnlyOnce() {
        UUID userId = UUID.randomUUID();
        EventEnvelope envelope = userCreated(userId, "grace", "grace@example.com");

        consumer.onEvent(envelope);
        consumer.onEvent(envelope);

        assertThat(customers.count()).isEqualTo(1);
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    /**
     * A different event carrying the same person. The eventId guard cannot see this
     * one -- it is genuinely a new event -- so only {@code UNIQUE (owner_id)} stops
     * a second customer row. This is why both guards exist rather than one.
     */
    @Test
    void convergesOnOwnerIdEvenWhenTheEventIdDiffers() {
        UUID userId = UUID.randomUUID();

        consumer.onEvent(userCreated(userId, "alan", "alan@example.com"));
        consumer.onEvent(userCreated(userId, "alan", "alan@example.com"));

        assertThat(customers.count()).isEqualTo(1);
        // Both events were recorded: the second was applied-then-found-redundant,
        // not rejected as a duplicate.
        assertThat(processedEvents.count()).isEqualTo(2);
    }

    /**
     * auth.events carries every event auth-server emits. Meeting one this build does
     * not model must not throw: throwing would fail the record, and a partition is
     * served in order, so the next unrelated event would never be reached.
     */
    @Test
    void ignoresAnEventTypeItDoesNotHandle() {
        assertThatCode(() -> consumer.onEvent(new EventEnvelope(
                UUID.randomUUID().toString(),
                "UserPasswordChanged",
                "User",
                UUID.randomUUID().toString(),
                1,
                Instant.now(),
                UUID.randomUUID().toString(),
                objectMapper.valueToTree(Map.of("userId", UUID.randomUUID().toString())))))
                .doesNotThrowAnyException();

        assertThat(customers.count()).isZero();
        // Still recorded, so a redelivery short-circuits instead of re-parsing.
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    /**
     * Version is checked rather than assumed. A newer producer may send a v2 payload
     * this code would silently misread as v1 -- the worst outcome, because it commits
     * wrong data instead of declining.
     */
    @Test
    void ignoresAPayloadVersionThisBuildCannotRead() {
        UUID userId = UUID.randomUUID();

        consumer.onEvent(new EventEnvelope(
                UUID.randomUUID().toString(),
                "UserCreated",
                "User",
                userId.toString(),
                2,
                Instant.now(),
                UUID.randomUUID().toString(),
                payloadNode(userId, "ada", "ada@example.com")));

        assertThat(customers.count()).isZero();
    }

    private EventEnvelope userCreated(UUID userId, String username, String email) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                "UserCreated",
                "User",
                userId.toString(),
                1,
                Instant.now(),
                UUID.randomUUID().toString(),
                payloadNode(userId, username, email));
    }

    private JsonNode payloadNode(UUID userId, String username, String email) {
        return objectMapper.valueToTree(new UserCreatedPayload(userId.toString(), username, email));
    }
}