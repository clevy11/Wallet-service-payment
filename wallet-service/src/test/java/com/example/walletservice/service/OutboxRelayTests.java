package com.example.walletservice.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.walletservice.domain.OutboxEvent;
import com.example.walletservice.domain.Wallet;
import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay, with the broker mocked out.
 *
 * <p>No {@code @EmbeddedKafka} here on purpose. auth-server's relay already pays for a
 * real broker, and this class is not testing Kafka -- it is testing the two decisions
 * that are easy to get wrong and impossible to see in a passing build:
 *
 * <ul>
 *   <li>that the relay is <em>scheduled</em> at all, and</li>
 *   <li>that it waits for the broker's acknowledgement before marking a row
 *       published.</li>
 * </ul>
 *
 * <p>Both were real bugs. The missing {@code @EnableScheduling} meant the relay never
 * ran: the app started cleanly, transfers succeeded, outbox rows were written, and
 * nothing was ever published. Every other test in the module passed, because they all
 * asserted on the outbox <em>row</em> -- which is exactly the row that sits still when
 * the relay is dead.
 */
class OutboxRelayTests extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @MockitoBean
    private KafkaTemplate<String, Object> kafka;

    /** The exact return type of {@code KafkaTemplate.send}, which is not a plain future. */
    private java.util.concurrent.CompletableFuture<SendResult<String, Object>> acknowledged() {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    @Value("${app.events.wallet-topic}")
    private String topic;

    /**
     * The regression this whole class exists for.
     *
     * <p>Asserted against the live context rather than by reading the annotation,
     * because the annotation being present is not the claim -- the task being
     * registered is. {@code @EnableScheduling} is a class-level switch several files
     * away from the {@code @Scheduled} it enables, and deleting it breaks nothing
     * that compiles.
     */
    @Test
    @DisplayName("the relay is actually scheduled")
    void theRelayIsScheduled() {
        assertThat(scheduledTasks.getScheduledTasks())
                .anySatisfy(task -> assertThat(task.getTask().getRunnable().toString())
                        .contains("publishPending"));
    }

    @Test
    @DisplayName("an acknowledged row is marked published and keyed by aggregate id")
    void publishesAndMarksAcknowledged() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(acknowledged());
        OutboxEvent event = givenUnpublishedEvent();

        relay().publishPending();

        verify(kafka).send(eq(topic), eq(event.getAggregateId().toString()), any());
        assertThat(outbox.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(outbox.findById(event.getId()).orElseThrow().getLastError()).isNull();
    }

    /**
     * The reason {@code send()} is followed by {@code get()}.
     *
     * <p>{@code send()} returns immediately, so marking the row published without
     * blocking on the result would record success for a message the broker may never
     * have accepted. A retryable failure would become permanent, silent event loss --
     * the one thing the outbox pattern exists to prevent.
     */
    @Test
    @DisplayName("a row is not marked published until the broker has acknowledged")
    void waitsForTheAcknowledgement() throws Exception {
        // A future that is never completed: exactly the shape of a send whose outcome
        // is still unknown.
        java.util.concurrent.CompletableFuture<SendResult<String, Object>> never =
                new java.util.concurrent.CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), any())).thenReturn(never);
        OutboxEvent event = givenUnpublishedEvent();

        relay().publishPending();

        assertThat(outbox.findById(event.getId()).orElseThrow().getPublishedAt())
                .as("published_at must stay NULL while the send is still in flight")
                .isNull();
    }

    @Test
    @DisplayName("a rejected send leaves the row for the next tick and records why")
    void failedSendIsRetriedNotDropped() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(
                java.util.concurrent.CompletableFuture.failedFuture(
                        new RuntimeException("broker down")));
        OutboxEvent event = givenUnpublishedEvent();

        relay().publishPending();

        OutboxEvent reloaded = outbox.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("broker down");
    }

    @Test
    @DisplayName("nothing is sent when there is nothing to send")
    void idleTickIsQuiet() {
        relay().publishPending();

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a published row is not sent again")
    void doesNotRepublish() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(acknowledged());
        OutboxEvent event = givenUnpublishedEvent();

        relay().publishPending();
        relay().publishPending();

        verify(kafka, times(1)).send(anyString(), anyString(), any());
        assertThat(event.getId()).isNotNull();
    }

    /** Built by hand rather than by transferring, so the test has one row to reason about. */
    private OutboxEvent givenUnpublishedEvent() {
        UUID ownerId = UUID.randomUUID();
        customers.createIfAbsent(UUID.randomUUID(), ownerId, "Relay");
        wallets.saveAndFlush(new Wallet(UUID.randomUUID(),
                customers.findByOwnerId(ownerId).orElseThrow().getId(), "USD",
                new BigDecimal("0.00")));

        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "Transfer", UUID.randomUUID(),
                "wallet.transfer.completed", UUID.randomUUID(), "{\"amount\":\"1.0000\"}");
        return outbox.saveAndFlush(event);
    }

    /** The real bean, so the scheduled-task and publishing paths are the production ones. */
    private OutboxRelay relay() {
        return context.getBean(OutboxRelay.class);
    }
}
