package com.example.walletservice.service;

import java.time.Instant;

import com.example.walletservice.domain.OutboxEvent;
import com.example.walletservice.repository.OutboxEventRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the relay's write-back transactions.
 *
 * <p>A separate bean for one reason: {@code @Transactional} is implemented with a
 * proxy around the bean, so a call from {@link OutboxRelay} to its own method
 * bypasses that proxy and runs with no transaction of its own. The code would still
 * appear to work -- {@code saveAndFlush} opens an implicit transaction from the
 * repository -- which is what makes this failure mode so quiet: the annotation reads
 * as protection that is not there. Crossing a bean boundary is what makes
 * {@code REQUIRES_NEW} real.
 *
 * <p>{@code REQUIRES_NEW} rather than the default, so marking published neither joins
 * any transaction the caller happens to be in nor is undone if the surrounding send
 * sequence later unwinds.
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
        event.markPublished();
        outbox.saveAndFlush(event);
    }

    /**
     * Records a failed attempt and leaves the row unpublished, so the next tick
     * retries it.
     *
     * <p>{@code attempts} and {@code last_error} exist for the case where retrying
     * never succeeds. Right now nothing acts on them: there is no dead-letter topic
     * and no cap on attempts, so a row that can never be published is retried
     * forever and quietly accumulates. Bounding that is a known gap, not an oversight.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(OutboxEvent event, String error) {
        event.markFailed(error);
        outbox.saveAndFlush(event);
    }
}
