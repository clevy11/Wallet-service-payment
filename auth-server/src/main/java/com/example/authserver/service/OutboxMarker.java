package com.example.authserver.service;

import java.time.Instant;

import com.example.authserver.domain.OutboxEvent;
import com.example.authserver.repository.OutboxEventRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the relay's write-back transactions.
 *
 * <p>A separate bean for one reason: {@code @Transactional} is implemented with a
 * proxy around the bean, so a call from {@code OutboxRelay} to its own method
 * bypasses the proxy entirely and runs with no transaction of its own. The code
 * would still appear to work -- {@code saveAndFlush} opens an implicit transaction
 * from the repository -- which is what makes this failure mode so quiet: the
 * annotation reads as protection that is not there. Calling across a bean boundary
 * is what makes {@code REQUIRES_NEW} real.
 *
 * <p>{@code REQUIRES_NEW} rather than the default: marking published must not join
 * any transaction the caller happens to be in, and must commit even if the send
 * sequence around it later unwinds.
 */
@Component
public class OutboxMarker {

    private final OutboxEventRepository outbox;

    public OutboxMarker(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    /** Called only after the broker has acknowledged the event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(OutboxEvent event) {
        event.markPublished(Instant.now());
        outbox.saveAndFlush(event);
    }

    /**
     * Records a failed attempt without marking the row published, so the next tick
     * retries it. {@code attempts} and {@code last_error} exist for the case where
     * retrying never succeeds.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(OutboxEvent event, String error) {
        event.markFailed(error);
        outbox.saveAndFlush(event);
    }
}