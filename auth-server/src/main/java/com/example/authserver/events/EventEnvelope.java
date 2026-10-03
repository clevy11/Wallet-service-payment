package com.example.authserver.events;

/**
 * The typed envelope every published event is wrapped in.
 *
 * <p>One topic per producing service carries many event types, so the envelope is
 * how a consumer learns what it actually received. It is also what makes the
 * system tolerant of change: a consumer that meets an {@code eventType} it does
 * not recognise can ignore it, and a producer can add event types without
 * touching any consumer's configuration.
 *
 * <p>This is auth-server's own copy of the contract. wallet-service holds a
 * duplicate of these records on purpose: under the no-shared-module rule, a
 * shared jar would let one service's edit to a contract break another's
 * compilation at a distance. Duplication is the isolation.
 *
 * @param eventId       stable identity of this event; the dedup key
 * @param eventType     what happened, e.g. {@code UserCreated}
 * @param aggregateType which kind of thing changed, e.g. {@code User}
 * @param aggregateId   which thing changed; also the partition key
 * @param version       schema version of {@code payload}
 * @param occurredAt    when the change was committed, not when it was published
 * @param correlationId ties this event to the request that caused it
 * @param payload       the event-specific body
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        int version,
        java.time.Instant occurredAt,
        String correlationId,
        T payload) {
}