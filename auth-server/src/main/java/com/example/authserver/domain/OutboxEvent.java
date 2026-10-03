package com.example.authserver.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row recording an event this service intends to publish. Written in the same
 * transaction as the business change that produced it.
 *
 * <p><b>Why this table exists.</b> Publishing straight from a service method
 * would need two writes -- one to Postgres, one to Kafka -- with no transaction
 * spanning both. The failure is silent and unrecoverable:
 *
 * <ul>
 *   <li>Crash between the DB commit and the Kafka send: the user exists, the
 *       event never fires, and no consumer ever learns about them.</li>
 *   <li>Kafka send succeeds, then the DB rolls back: consumers react to a user
 *       that does not exist.</li>
 * </ul>
 *
 * <p>Holding a transaction open across the send does not fix it -- that just
 * pins a connection for the duration of a network call you do not control, and
 * the second failure mode is unchanged. Writing the intent durably first and
 * relaying it separately does.
 *
 * <p><b>What this does not solve.</b> If the relay dies after Kafka accepts the
 * message but before {@code published_at} is set, the row is still unpublished
 * and gets re-sent. That is expected: the outbox removes event <em>loss</em>,
 * not <em>duplication</em>. Removing duplication is the consumer's job, which is
 * why every event carries a stable {@code id} for it to dedup on.
 *
 * <p>No setters: a published flag and its bookkeeping are the only fields that
 * change after insert, and they are set by the relay alone.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /**
     * This id is the {@code eventId} in the published envelope and the only
     * identity a consumer can dedup on. Generated once, here, at write time.
     */
    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    /**
     * Also the Kafka message key. That is deliberate: keying by aggregate id
     * pins every event about one aggregate to a single partition, so a consumer
     * group processes them one at a time and in order. Keyless or round-robin
     * sends would scatter them across partitions and allow concurrent handling.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Setter(AccessLevel.NONE)
    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Setter(AccessLevel.NONE)
    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    /**
     * Taken from the inbound request, not minted by the relay. Captured here so
     * the committed row already carries it: a relay-generated id would identify
     * nothing and break the trace between request and consumer.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Setter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Setter(AccessLevel.NONE)
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** {@code null} means unpublished. The relay scans for exactly this state. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
                       int schemaVersion, UUID correlationId, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.correlationId = correlationId;
        this.payload = payload;
        this.occurredAt = Instant.now();
    }

    /** Called only by the relay, and only after Kafka has acknowledged. */
    public void markPublished(Instant when) {
        this.publishedAt = when;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 512));
    }
}