package com.example.walletservice.web;

import java.util.UUID;

/**
 * Correlates a request with everything it causes, across services.
 *
 * <p>wallet-service's own copy of auth-server's helper, for the same reason it keeps
 * its own copy of the event envelope: there is no shared module, and a shared one
 * would couple two services' release cycles for a twenty-line class.
 *
 * <p>An inbound {@code X-Correlation-Id} is reused so the whole causal chain -- the
 * API call here, the {@code wallet.transfer.completed} event on the topic, and
 * whatever a downstream service does with it -- carries one id. If the header is
 * absent or malformed a fresh id is minted <em>here, at the edge</em>, never by the
 * relay: an id generated at publish time identifies nothing, because it can no longer
 * be matched back to the request that caused the transfer.
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
            // A caller's malformed id must not fail the request. Mint a valid one so
            // the trace still works for this call -- rejecting it would turn a typo in
            // a tracing header into an outage with no cause.
            return UUID.randomUUID();
        }
    }
}
