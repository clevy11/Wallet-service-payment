package com.example.authserver.repository;

import java.util.List;
import java.util.UUID;

import com.example.authserver.domain.OutboxEvent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The relay's only query: unpublished rows, oldest first.
     *
     * <p>Oldest first so a backlog drains in the order it accumulated. Backed by
     * the partial index {@code idx_outbox_unpublished}, so cost tracks the size of
     * the backlog rather than the table.
     *
     * <p>Deliberately <em>not</em> {@code FOR UPDATE SKIP LOCKED}. That would let
     * several relay instances work in parallel, but the lock would have to be held
     * for the duration of the Kafka round trip -- a database transaction spanning a
     * network call, which is exactly the mistake the outbox exists to avoid. For a
     * single relay the simpler read-then-send is correct, and a relay that dies
     * mid-batch simply re-sends rows that stay unpublished. Consumers are idempotent
     * for precisely that reason.
     */
    List<OutboxEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);
}