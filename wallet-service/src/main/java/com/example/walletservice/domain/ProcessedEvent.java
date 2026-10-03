package com.example.walletservice.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Record of an event that has already been applied, keyed by the envelope's
 * {@code eventId}.
 *
 * <p>Needed because a unique constraint on a business key only protects against
 * <em>state-setting</em> events, where replaying converges on the same result
 * (create this customer, set this status). A relative operation cannot be
 * protected that way: replaying "debit 50" twice must not charge twice, and two
 * legitimate debits of 50 are indistinguishable by business key. So ledger-affecting
 * events are deduped here on the message identity instead.
 *
 * <p>The insert and the business change belong in the same transaction as the
 * ledger write. Commit the effect without this row and a redelivery double-charges;
 * commit this row without the effect and the event is lost.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}