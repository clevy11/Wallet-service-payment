package com.example.walletservice.domain;

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
 * A row recording an event this service intends to publish, written in the same
 * transaction as the business change that produced it.
 *
 * <p>The reasoning is identical to auth-server's copy, and deliberately so -- there
 * is no shared {@code common} module, because each service owns its event contracts
 * and its own database. What that costs is this duplication; what it buys is that
 * neither service's schema can be changed by a deploy of the other.
 *
 * <p><b>Why the table exists.</b> Writing to Postgres and to Kafka from one service
 * method means two writes with no transaction spanning both. The failures are
 * silent:
 *
 * <ul>
 *   <li>Crash between the DB commit and the Kafka send: the money moved and the
 *       event never fires, so no consumer ever learns it happened.</li>
 *   <li>Kafka send succeeds, then the DB rolls back: consumers react to a transfer
 *       that did not happen.</li>
 * </ul>
 *
 * <p>Holding a transaction open across the send fixes neither -- it pins a database
 * connection for the length of a network call, and the second failure mode stands.
 * Writing the intent durably first and relaying it separately does.
 *
 * <p><b>What it does not solve.</b> A relay that dies after Kafka accepts the message
 * but before {@code published_at} is set re-sends the row. The outbox removes event
 * <em>loss</em>, not <em>duplication</em>; removing duplication is the consumer's job.
 *
 * @see com.example.walletservice.service.OutboxRelay
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Setter(AccessLevel.NONE)
    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    /**
     * Doubles as the Kafka message key. Same partition means one consumer handles
     * every event for one transfer, in order.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Setter(AccessLevel.NONE)
    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Setter(AccessLevel.NONE)
    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion = 1;

    /**
     * Read from {@code X-Correlation-Id} when the request arrived, not minted here.
     * An id generated at publish time would identify nothing: it could not be
     * matched back to the request that caused the transfer.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    /**
     * Stored as text and written with the application's ObjectMapper, not as a
     * Hibernate JSON type. {@code JSONB} normalises key order and whitespace, so
     * reading a row back and comparing it to the string that was written is
     * unreliable -- the database is entitled to return a different byte sequence
     * that means the same thing.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Setter(AccessLevel.NONE)
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** NULL means "not yet published". The relay scans for exactly this. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
                       UUID correlationId, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.correlationId = correlationId;
        this.payload = payload;
        this.occurredAt = Instant.now();
    }

    /**
     * A method rather than a setter, for the same reason {@code @Setter(AccessLevel.NONE)}
     * covers the fields above: {@code published_at} has exactly one legal
     * transition, and allowing arbitrary assignment would let a caller mark a row
     * published that was never sent.
     */
    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Records the failure and leaves {@code publishedAt} null, so the next relay
     * tick picks the row up again. The message is truncated rather than allowed to
     * fail the update: an over-long error string must not stop the retry.
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
}