package com.example.walletservice.events;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/**
 * wallet-service's copy of the {@code auth.events} envelope.
 *
 * <p>An intentional duplicate of auth-server's record. Under the no-shared-module
 * rule, a shared contract jar would let one service's edit break another's
 * compilation across the repo, coupling release cycles that are supposed to be
 * independent. Duplication here is the isolation: auth-server can change its
 * payload without wallet-service failing to build, which is what you want while the
 * two deploy on different schedules.
 *
 * <p><b>Now that wallet-service also produces, this record serves both roles.</b> The
 * {@code wallet.events} relay builds one of these too, and it could be generic -- the
 * relay knows it is publishing a transfer. It deliberately is not. Two records with
 * an identical wire shape and a type parameter as the only difference would have to be
 * kept in sync forever, and the parameter buys nothing here: the relay reads the
 * payload back as a {@link JsonNode} regardless, because the payload type is stored
 * as data in the outbox row rather than known at compile time. One record, one shape,
 * one thing to keep correct.
 *
 * <p><b>Why this copy is not generic while auth-server's is.</b> The producer knows
 * the payload type at compile time -- it is constructing the event, so
 * {@code EventEnvelope<UserCreatedPayload>} is checked by javac. A consumer on a
 * shared topic cannot: {@code auth.events} will eventually carry
 * {@code UserRevoked}, {@code UserPasswordChanged} and types this service has never
 * heard of, and the right target class is only known at runtime from
 * {@code eventType}.
 *
 * <p>Making the consumer copy generic would mean lying to the deserialiser. Asking
 * for {@code EventEnvelope<UserCreatedPayload>} would either silently hand back a
 * {@code LinkedHashMap} for the payload -- failing later as a
 * {@code ClassCastException} deep in business logic -- or need a type header naming
 * the producer's class, which leaks producer internals onto the wire and couples the
 * consumer to the producer's package layout. Holding the payload as a
 * {@link JsonNode} and converting per {@code eventType} is the honest form: unparsed
 * is exactly what "I do not yet know what this is" means.
 *
 * <p>The cost of that isolation is that a genuine contract change is not caught at
 * compile time. It surfaces at runtime instead, which is why {@code version} and
 * {@code eventType} are dispatched on explicitly rather than trusted, and why unknown
 * fields must be tolerated.
 *
 * @param eventId       stable identity of this event; the dedup key
 * @param eventType     what happened, e.g. {@code UserCreated}
 * @param aggregateType which kind of thing changed, e.g. {@code User}
 * @param aggregateId   which thing changed; also the Kafka message key
 * @param version       schema version of {@code payload}
 * @param occurredAt    when the change was committed, not when it was published
 * @param correlationId ties this event back to the request that caused it
 * @param payload       the event-specific body, still unparsed
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        int version,
        Instant occurredAt,
        String correlationId,
        JsonNode payload) {
}