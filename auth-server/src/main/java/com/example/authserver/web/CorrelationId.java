package com.example.authserver.web;

import java.util.UUID;

/**
 * Correlates a request with everything it causes, across services.
 *
 * <p>Propagated in the {@code X-Correlation-Id} header. If the inbound request
 * carries one, that value is reused so the whole causal chain shares an id; if not,
 * a fresh one is minted <em>here, at the edge</em>, never by the publisher. A
 * correlation id generated at publish time identifies nothing -- it would not
 * connect the event to the request that caused it.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";

    private CorrelationId() {
    }

    public static UUID resolve(String inboundHeaderValue) {
        if (inboundHeaderValue == null || inboundHeaderValue.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(inboundHeaderValue.trim());
        } catch (IllegalArgumentException e) {
            // A malformed id from a caller must not fail the request; mint a valid
            // one so the trace still works for this call.
            return UUID.randomUUID();
        }
    }
}