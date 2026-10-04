package com.example.walletservice.service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.example.walletservice.domain.OutboxEvent;
import com.example.walletservice.events.EventEnvelope;
import com.example.walletservice.repository.OutboxEventRepository;

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
 * The relay: drains wallet-service's {@code outbox_events} to {@code wallet.events}.
 *
 * <p>An intentional near-copy of auth-server's relay. Each service owns its event
 * contracts, and the agreed trade-off is that duplication between producer and consumer
 * is the price of not having a shared {@code common} module -- either service's
 * schema and envelope can change without a coordinated deploy of the other.
 *
 * <p>Each row is published, then marked published <em>in a separate transaction</em>.
 * That ordering is the design, and its failure modes are the point:
 *
 * <ul>
 *   <li>Kafka rejects the send -> the row stays unpublished and is retried. No loss.</li>
 *   <li>Kafka accepts, then the process dies before the update commits -> the row is
 *       still unpublished and is sent again. A duplicate, which consumers must handle
 *       idempotently. No loss: delivery is at-least-once.</li>
 * </ul>
 *
 * <p>Doing both in one transaction would be worse than either. Rollback after a
 * successful send re-sends it -- the same duplicate as above -- while
 * commit-without-a-send silently drops the event, and the send could not be rolled
 * back anyway since it is not part of this transaction.
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
                       @Value("${app.events.wallet-topic}") String topic,
                       @Value("${app.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.marker = marker;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms}",
               initialDelayString = "${app.outbox.initial-delay-ms}")
    public void publishPending() {
        List<OutboxEvent> pending =
                outbox.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, batchSize));

        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            // Keyed by aggregateId, which for a transfer is the transfer id. Every
            // event about one transfer therefore lands in one partition and one
            // consumer handles it in order. A keyless send would round-robin across
            // partitions and allow related events to be processed concurrently.
            //
            // get(timeout) is not optional. send() is asynchronous, so returning
            // before the broker answers would let this method mark a row published
            // that was never sent -- turning a retryable failure into permanent,
            // silent event loss.
            kafka.send(topic, event.getAggregateId().toString(), toEnvelope(event))
                    .get(10, TimeUnit.SECONDS);

            marker.markPublished(event);
        } catch (InterruptedException e) {
            // Preserve the flag: swallowing it stops the JVM honouring a shutdown
            // request part-way through a batch.
            Thread.currentThread().interrupt();
            marker.markFailed(event, "interrupted while publishing");
        } catch (Exception e) {
            // Kafka down, topic missing, unserialisable payload: all leave the row
            // unpublished so the next tick retries it. Nothing is lost, and the loop
            // keeps running instead of dying with the exception.
            marker.markFailed(event, e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("outbox publish failed for event {} ({}), will retry",
                    event.getId(), event.getEventType(), e);
        }
    }

    /**
     * Built from the row's columns. The relay never invents a field:
     * {@code correlationId} in particular is read from the row, because an id minted
     * here would identify nothing -- it could not be traced back to the request that
     * caused the transfer.
     */
    private EventEnvelope toEnvelope(OutboxEvent event) {
        return new EventEnvelope(
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
     * Parsed to a tree rather than into a typed record: the relay does not know which
     * payload class a row holds, because the event type is data in the row and a new
     * type needs no relay change. A tree also keeps numbers as numbers and nests as
     * nests -- deserialising to {@code Map<String, Object>} would coerce every number
     * to a double, so 10.00 could return as 10.0 and a large id lose precision.
     */
    private JsonNode readPayload(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored outbox payload is not valid JSON", e);
        }
    }
}
