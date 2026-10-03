package com.example.authserver;

import java.util.List;
import java.util.UUID;

import com.example.authserver.domain.OutboxEvent;
import com.example.authserver.domain.User;
import com.example.authserver.support.PostgresIntegrationTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the two things a schema slice has to get right: that Flyway's migration and
 * Hibernate's mappings agree, and that the constraints doing the safety work fire.
 *
 * <p>The second half matters more than it looks. The relay's correctness rests on
 * {@code published_at IS NULL} meaning "unpublished", and its performance rests on
 * the partial index existing. Neither is visible in Java, and Hibernate's
 * {@code ddl-auto: validate} would start happily without the index.
 */
class AuthSchemaTests extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V1 migration ran and left both tables behind")
    void migrationApplied() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' order by table_name",
                String.class);

        assertThat(tables)
                .contains("users", "outbox_events")
                .contains("flyway_schema_history");
    }

    @Test
    @DisplayName("usernames are unique regardless of case")
    void usernameUniquenessFoldsCase() {
        users.saveAndFlush(new User(UUID.randomUUID(), "Ada", "ada@example.com", "hash"));

        assertThatThrownBy(() -> users.saveAndFlush(
                        new User(UUID.randomUUID(), "ada", "other@example.com", "hash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a user needs a password hash")
    void passwordHashIsRequired() {
        assertThatThrownBy(() -> users.saveAndFlush(
                        new User(UUID.randomUUID(), "ada", "ada@example.com", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The relay's only query. Without this index it still returns the right rows, just
     * by scanning the whole table -- which grows forever and is never pruned, so the
     * cost of publishing one event would rise with total history rather than with
     * backlog.
     */
    @Test
    @DisplayName("the relay's unpublished scan is backed by a partial index")
    void unpublishedScanIsIndexed() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes "
                        + "where tablename = 'outbox_events' order by indexname",
                String.class);

        assertThat(indexes).contains("idx_outbox_unpublished");
    }

    @Test
    @DisplayName("an outbox row carries everything the envelope needs")
    void outboxRowIsSelfDescribing() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        outbox.saveAndFlush(new OutboxEvent(eventId, "User", aggregateId, "UserCreated",
                1, correlationId, "{\"userId\":\"" + aggregateId + "\"}"));

        OutboxEvent row = outbox.findById(eventId).orElseThrow();

        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getAggregateId()).isEqualTo(aggregateId);
        assertThat(row.getCorrelationId()).isEqualTo(correlationId);
        assertThat(row.getPayload()).contains(aggregateId.toString());
    }

    @Test
    @DisplayName("the payload is real JSONB, so it can be queried")
    void payloadIsJsonbNotText() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        outbox.saveAndFlush(new OutboxEvent(eventId, "User", aggregateId, "UserCreated",
                1, UUID.randomUUID(), "{\"userId\":\"" + aggregateId + "\",\"email\":\"a@b.c\"}"));

        // A jsonb column answers a predicate on its contents; a text column storing
        // the same bytes would not. This is what makes "find every event for this
        // user" possible later without a schema change.
        Integer matches = jdbc.queryForObject(
                "select count(*) from outbox_events where payload->>'userId' = ?",
                Integer.class, aggregateId.toString());

        assertThat(matches).isEqualTo(1);
    }

    @Test
    @DisplayName("an event without a correlation id is refused")
    void correlationIdIsRequired() {
        assertThatThrownBy(() -> outbox.saveAndFlush(new OutboxEvent(
                UUID.randomUUID(), "User", UUID.randomUUID(), "UserCreated",
                1, null, "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}