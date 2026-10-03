package com.example.walletservice.messaging;

import java.util.UUID;

import com.example.walletservice.domain.ProcessedEvent;
import com.example.walletservice.events.EventEnvelope;
import com.example.walletservice.events.UserCreatedPayload;
import com.example.walletservice.repository.CustomerRepository;
import com.example.walletservice.repository.ProcessedEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code auth.events} and provisions a customer when a user appears.
 *
 * <p>This is where a duplicate delivery is absorbed. The outbox guarantees
 * at-least-once, so this method will be called twice for the same event in normal
 * operation -- whenever the relay dies between Kafka's acknowledgement and its own
 * {@code published_at} update. Being called twice must be harmless.
 *
 * <p>Deduplication is two independent guards, because they answer different
 * questions:
 *
 * <ul>
 *   <li>{@code processed_events} keyed on {@code eventId}: "have I already applied
 *       this exact envelope?"</li>
 *   <li>{@code UNIQUE (owner_id)} on customers: "does this person already have a
 *       row?" -- which also covers a different event implying the same customer.</li>
 * </ul>
 *
 * <p>For {@code UserCreated} the second guard alone would be enough, because
 * auth-server generated the user id, so a replay re-derives the same
 * {@code owner_id}: creating a customer is a state-setting operation where replay
 * converges. Day 3's "debit 50" is relative -- replaying it charges twice, no
 * business key distinguishes a replay from a legitimate repeat, and the eventId
 * guard becomes the only defence. Both guards are cheap, so both are kept.
 */
@Component
public class UserCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserCreatedConsumer.class);

    /** Schema version this service knows how to read. */
    private static final int SUPPORTED_VERSION = 1;

    private final ProcessedEventRepository processedEvents;
    private final CustomerRepository customers;
    private final ObjectMapper objectMapper;

    public UserCreatedConsumer(ProcessedEventRepository processedEvents,
                               CustomerRepository customers,
                               ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.customers = customers;
        this.objectMapper = objectMapper;
    }

    // No ackMode here: it is a container-factory setting, not a listener one.
    // Configured as spring.kafka.listener.ack-mode in application.yml.
    @KafkaListener(
            topics = "${app.events.auth-topic}",
            groupId = "${spring.application.name}")
    @Transactional
    public void onEvent(EventEnvelope envelope) {
        UUID eventId = UUID.fromString(envelope.eventId());
        String correlationId = envelope.correlationId();

        log.info("received eventType={} eventId={} correlationId={} aggregateId={}",
                envelope.eventType(), eventId, correlationId, envelope.aggregateId());

        // Written in the same transaction as the business change below. Commit the
        // effect without this row and a redelivery applies it twice; commit this row
        // without the effect and the event is lost forever.
        int firstSighting = processedEvents.insertIfAbsent(eventId, envelope.eventType());
        if (firstSighting == 0) {
            log.info("eventId={} already applied, skipping", eventId);
            return;
        }

        // Dispatch on type, not on topic: auth.events is a stream of every event
        // auth-server emits, so anything this service does not model yet arrives
        // here too.
        switch (envelope.eventType()) {
            case "UserCreated" -> onUserCreated(envelope, correlationId);
            default -> log.info("no handler for eventType={}, eventId={} recorded and ignored",
                    envelope.eventType(), eventId);
        }
    }

    private void onUserCreated(EventEnvelope envelope, String correlationId) {
        if (envelope.version() != SUPPORTED_VERSION) {
            // Recorded in processed_events and skipped, rather than failed. Retrying
            // cannot help: this build will never understand v2, and rethrowing
            // would stall the partition, blocking every later event behind it.
            log.warn("ignoring UserCreated version={}, this build supports {}",
                    envelope.version(), SUPPORTED_VERSION);
            return;
        }

        UserCreatedPayload payload =
                objectMapper.convertValue(envelope.payload(), UserCreatedPayload.class);
        UUID ownerId = UUID.fromString(payload.userId());

        int created = customers.createIfAbsent(
                UUID.randomUUID(), ownerId, payload.username());

        if (created == 1) {
            log.info("provisioned customer ownerId={} correlationId={}", ownerId, correlationId);
        } else {
            log.info("customer already exists for ownerId={}, nothing to do", ownerId);
        }
    }
}