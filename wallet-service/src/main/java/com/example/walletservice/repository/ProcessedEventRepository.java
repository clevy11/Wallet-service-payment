package com.example.walletservice.repository;

import java.util.UUID;

import com.example.walletservice.domain.ProcessedEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByEventId(UUID eventId);

    /**
     * Records that an event has been applied, and reports whether this call was
     * the one that inserted it.
     *
     * <p>Written as {@code INSERT ... ON CONFLICT DO NOTHING} rather than
     * {@code save()} for two reasons.
     *
     * <p>First, {@link ProcessedEvent} has a manually assigned identifier, so
     * {@code save()} calls {@code merge()}: it SELECTs first, and if a row
     * already exists it issues an UPDATE instead of an INSERT. A duplicate
     * delivery would therefore <em>overwrite</em> the existing row rather than
     * colliding with the unique constraint -- the dedup would appear to work only
     * by accident, and the unique index would never be doing its job.
     *
     * <p>Second, this is one statement, so there is no window between "have I
     * seen this?" and "record that I have seen it". A check-then-insert pair
     * races with a concurrent delivery of the same event.
     *
     * @return 1 if this caller inserted the row and should apply the event,
     *         0 if it was already recorded and the event must be skipped
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into processed_events (event_id, event_type, processed_at)
            values (:eventId, :eventType, now())
            on conflict (event_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("eventType") String eventType);
}