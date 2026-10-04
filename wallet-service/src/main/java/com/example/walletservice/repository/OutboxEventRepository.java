package com.example.walletservice.repository;

import java.util.List;
import java.util.UUID;

import com.example.walletservice.domain.OutboxEvent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The relay's only query: unpublished rows, oldest first, so a backlog drains in
     * the order it accumulated. Backed by the partial index
     * {@code idx_outbox_unpublished}, so the cost tracks the backlog rather than the
     * table.
     *
     * <p>Deliberately not {@code FOR UPDATE SKIP LOCKED}. That would let several
     * relays work in parallel, but the row lock would have to be held across the
     * Kafka round trip -- a database transaction spanning a network call, which is
     * precisely the mistake the outbox exists to prevent. With one relay the simpler
     * read-then-send is correct, and a relay that dies mid-batch re-sends rows that
     * are still unpublished. Consumers are idempotent for exactly that reason.
     */
    List<OutboxEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);
}