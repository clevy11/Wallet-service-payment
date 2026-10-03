package com.example.authserver.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.example.authserver.domain.OutboxEvent;
import com.example.authserver.repository.OutboxEventRepository;
import com.example.authserver.support.PostgresIntegrationTest;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The failure half of the outbox contract, which the happy-path relay test cannot
 * reach: when Kafka refuses the message, the row must stay unpublished.
 *
 * <p>Without a stubbed failure the bug is invisible. Marking the row published after
 * a send that went nowhere is the one mistake that turns the outbox from "at least
 * once" into "at most once", and it deletes the only copy of the event: the outbox row
 * was the sole record that the user existed and had to be announced.
 *
 * <p>Kafka is stubbed here rather than embedded because the assertion is about what
 * the relay does when the send fails, not about Kafka's own failure modes.
 */
@TestPropertySource(properties = {
        "app.outbox.fixed-delay-ms=600000",
        "app.outbox.initial-delay-ms=600000"
})
class OutboxRelayFailureTests extends PostgresIntegrationTest {

    @Autowired
    private UserRegistrationService registrations;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> failingKafka = mock(KafkaTemplate.class);

    @Test
    @DisplayName("a rejected send leaves the row unpublished so it is retried")
    void doesNotMarkPublishedWhenKafkaRejects() {
        UUID eventId = givenAnUnpublishedEvent();

        OutboxRelay relay = new OutboxRelay(outbox, new OutboxMarker(outbox), failingKafka,
                objectMapper, "auth.events", 50);

        relay.publishPending();

        OutboxEvent row = outbox.findById(eventId).orElseThrow();
        assertThat(row.getPublishedAt())
                .as("marking a failed send as published deletes the only copy of the event")
                .isNull();
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNotBlank();
    }

    /**
     * One failure must not stop the batch. A poison message that throws out of the loop
     * would block every event behind it in the backlog -- the publisher would keep
     * retrying the same row first and nothing would ever drain.
     */
    @Test
    @DisplayName("one failed event does not stop the others in the batch")
    void keepsGoingAfterAFailure() {
        UUID failing = givenAnUnpublishedEvent();
        UUID following = givenAnUnpublishedEvent();

        // The first send fails, the second succeeds.
        when(failingKafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(acknowledged()));

        OutboxRelay relay = new OutboxRelay(outbox, new OutboxMarker(outbox), failingKafka,
                objectMapper, "auth.events", 50);
        relay.publishPending();

        assertThat(outbox.findById(failing).orElseThrow().getPublishedAt()).isNull();
        assertThat(outbox.findById(following).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("a successful send marks the row published")
    void marksPublishedOnSuccess() {
        UUID eventId = givenAnUnpublishedEvent();

        when(failingKafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(acknowledged()));

        OutboxRelay relay = new OutboxRelay(outbox, new OutboxMarker(outbox), failingKafka,
                objectMapper, "auth.events", 50);
        relay.publishPending();

        assertThat(outbox.findById(eventId).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("the message is keyed by aggregate id")
    void keysByAggregateId() {
        UUID eventId = givenAnUnpublishedEvent();
        OutboxEvent event = outbox.findById(eventId).orElseThrow();

        when(failingKafka.send(eq("auth.events"), eq(event.getAggregateId().toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(acknowledged()));

        new OutboxRelay(outbox, new OutboxMarker(outbox), failingKafka, objectMapper,
                "auth.events", 50).publishPending();

        org.mockito.Mockito.verify(failingKafka)
                .send(eq("auth.events"), eq(event.getAggregateId().toString()), any());
    }

    /** A completed future is all the relay inspects -- it never reads the metadata. */
    private SendResult<String, Object> acknowledged() {
        return new SendResult<>(
                new org.apache.kafka.clients.producer.ProducerRecord<>("auth.events", 0, "k", null),
                mock(RecordMetadata.class));
    }

    private UUID givenAnUnpublishedEvent() {
        UserRegistrationService.RegistrationResult result =
                registrations.register("ada-" + UUID.randomUUID(), "ada@example.com",
                        "pw", UUID.randomUUID());
        return result.outboxEventId();
    }
}