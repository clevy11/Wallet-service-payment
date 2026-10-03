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

/**
 * Record of an event that has already been applied, keyed by the envelope's
 * {@code eventId}.
 *
 * <p>Needed because a unique constraint on a business key only protects against
 * <em>state-setting</em> events, where replaying converges on the same result
 * (create this customer, set this status). A relative operation cannot be
 * protected that way: replaying "debit 50" twice must not charge twice, and two
 * legitimate debits of 50 are indistinguishable by business key. So
 * ledger-affecting events are deduped here on the message identity instead.
 *
 * <p>The insert and the business change belong in the same transaction as the
 * ledger write. Commit the effect without this row and a redelivery
 * double-charges; commit this row without the effect and the event is lost.
 *
 * <p><b>No {@code @Setter}, unlike the other entities.</b> This row is an
 * append-only attestation that something already happened. Two reasons that is
 * not merely a style preference:
 *
 * <ol>
 *   <li>It is the record the idempotency guard trusts. Being able to edit
 *       "this was already processed" means being able to defeat dedup.</li>
 *   <li>Every column is {@code updatable = false}, so a setter would be worse
 *       than useless: Hibernate builds UPDATE statements that exclude those
 *       columns, so {@code setEventType("X")} would change the object in memory,
 *       write nothing, and silently revert on the next read. Code that appears
 *       to work and does not is the worst outcome available.</li>
 * </ol>
 *
 * <p>{@code @Getter} is still required: not by Hibernate, which uses field
 * access here, but by the consumer that inspects a previously recorded event and
 * by anything that serialises one.
 *
 * <p>The no-arg constructor is not optional. JPA requires one to hydrate an
 * entity, and its absence is only a WARN at startup -- it throws
 * {@code InstantiationException} the first time anything actually loads a row.
 *
 * <p>See {@link Customer} for why the other entities use {@code @Getter}
 * rather than {@code @Data}.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    public ProcessedEvent(UUID eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }
}