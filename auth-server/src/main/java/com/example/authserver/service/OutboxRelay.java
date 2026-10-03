package com.example.authserver.service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.example.authserver.domain.OutboxEvent;
import com.example.authserver.events.EventEnvelope;
import com.example.authserver.repository.OutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The relay: drains {@code outbox_events} to {@code auth.events}.
 *
 * <p>Each row is published, then marked published <em>in a separate transaction</em>.
 * That ordering is the whole design, and its failure mode is the point:
 *
 * <ul>
 *   <li>Kafka rejects the send -> the row stays unpublished and is retried. No loss.
 *   <li>Kafka accepts, then the process dies before the update commits -> the row
 *       is still unpublished and is sent again. A duplicate, which the consumer
 *       handles idempotently. No loss, at-least-once delivery.</li>
 * </ul>
 *
 * <p>Doing both in one transaction instead would be worse than either: rollback
 * after a successful send re-sends (same as above), while commit-without-a-send
 * silently drops the event, and the send cannot be rolled back anyway because it
 * is not part of this transaction.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final OutboxMarker marker;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(OutboxEventRepository outbox, OutboxMarker marker,
                       KafkaTemplate<String, Object> kafka, ObjectMapper objectMapper,
                       @Value("${app.events.auth-topic}") String topic,
                       @Value("${app.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.marker = marker;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.batchSize = batchSize;
    }

/**
     * The {@code initialDelay} is not cosmetic: without it every instance starts
     * scanning at the same instant after boot, so N instances hit the same backlog
     * together. On restart -- which is exactly when the backlog is largest, because
     * it accumulated while the relay was down -- that is a synchronised load spike on
     * both the database and the broker.
 */
@Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms}",
           initialDelayString = "${app.outbox.initial-delay-ms}")
public void publishPending() {
        List<OutboxEvent> pending =
                outbox.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, batchSize));

        if (pending.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            EventEnvelope<?> envelope = toEnvelope(event);

            // Keyed by aggregateId: every event about one aggregate lands in the
            // same partition, so one consumer handles them in order. Keyless sends
            // would round-robin across partitions and allow concurrent handling of
            // related events.
            //
            // get(timeout) is essential. send() is asynchronous, so without blocking
            // on the result this method would mark the row published even when the
            // send failed -- converting a retryable error into permanent, silent
            // event loss.
            kafka.send(topic, event.getAggregateId().toString(), envelope)
                    .get(10, TimeUnit.SECONDS);

            marker.markPublished(event);
        } catch (InterruptedException e) {
            // Preserve the interrupt flag: swallowing it would stop the JVM honouring
            // a shutdown request mid-batch.
            Thread.currentThread().interrupt();
            marker.markFailed(event, "interrupted while publishing");
        } catch (Exception e) {
            // Kafka down, topic missing, serialisation failure: all leave the row
            // unpublished, so the next tick retries it. Nothing is lost, and the
            // loop keeps running instead of dying with the exception.
            marker.markFailed(event, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("outbox publish failed for event {} ({}), will retry",
                    event.getId(), event.getEventType(), e);
        }
    }

    /**
     * Builds the envelope straight from the row's columns. The relay never invents
     * a field: {@code correlationId} in particular is read from the row rather than
     * generated here, because an id minted at publish time would trace nothing back
     * to the request that caused the event.
     */
    private EventEnvelope<?> toEnvelope(OutboxEvent event) {
        return new EventEnvelope<>(
                event.getId().toString(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId().toString(),
                event.getSchemaVersion(),
                event.getOccurredAt(),
                event.getCorrelationId().toString(),
                readPayload(event.getPayload()));
    }

    /**
     * Reads the stored payload as a JSON tree rather than into a typed record.
     *
     * <p>The relay does not know which payload class a row holds -- the event type
     * is data in the row, and a new type needs no relay change. Parsing to a tree
     * keeps numbers as numbers and nests as nests; deserialising to
     * {@code Map<String, Object>} would coerce every number to a double, so an
     * amount of 10.00 could come back as 10.0 and a large id lose precision.
     */
    private JsonNode readPayload(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored outbox payload is not valid JSON", e);
        }
    }
}