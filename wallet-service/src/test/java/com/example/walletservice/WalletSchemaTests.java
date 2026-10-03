package com.example.walletservice;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.example.walletservice.domain.Customer;
import com.example.walletservice.domain.EntryType;
import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletEntry;
import com.example.walletservice.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the two things a schema slice has to get right: that Flyway's migration
 * and Hibernate's mappings actually agree, and that the constraints doing the
 * safety work really fire.
 */
class WalletSchemaTests extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V1 migration ran and left the four tables behind")
    void migrationApplied() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' order by table_name",
                String.class);

        assertThat(tables)
                .contains("customers", "wallets", "wallet_entries", "processed_events")
                .contains("flyway_schema_history");
    }

    @Test
    @DisplayName("every table Hibernate maps has a Flyway migration behind it")
    void migrationCounted() {
        // If the entity and the migration ever drift, ddl-auto:validate makes the
        // context fail to start before this test runs. Reaching here at all is
        // the assertion.
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("a wallet cannot hold a negative balance")
    void negativeBalanceRejected() {
        Customer customer = customers.saveAndFlush(
                new Customer(UUID.randomUUID(), UUID.randomUUID(), "Ada", "USD"));

        Assertions.assertThatThrownBy(() ->
                        wallets.saveAndFlush(new Wallet(UUID.randomUUID(), customer.getId(),
                                "USD", new BigDecimal("-0.0001"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a duplicate UserCreated delivery cannot create two customers")
    void duplicateOwnerIdRejected() {
        UUID ownerId = UUID.randomUUID();
        customers.saveAndFlush(new Customer(UUID.randomUUID(), ownerId, "Ada", "USD"));

        // This is the idempotency guard in action: the second delivery of the
        // same event loses on the unique constraint instead of inserting a
        // duplicate row. The consumer catches this and treats it as success.
        Assertions.assertThatThrownBy(() ->
                        customers.saveAndFlush(
                                new Customer(UUID.randomUUID(), ownerId, "Ada Again", "USD")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(customers.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retried debit with the same reference is rejected, not charged twice")
    void duplicateReferenceRejected() {
        UUID walletId = givenWalletWithBalance("100.0000");

        entries.saveAndFlush(new WalletEntry(UUID.randomUUID(), walletId,
                EntryType.WITHDRAWAL, new BigDecimal("50.0000"), new BigDecimal("50.0000"), "pi-1"));

        Assertions.assertThatThrownBy(() ->
                        entries.saveAndFlush(new WalletEntry(UUID.randomUUID(), walletId,
                                EntryType.WITHDRAWAL, new BigDecimal("50.0000"),
                                new BigDecimal("0.0000"), "pi-1")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(entries.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("two ledger rows may share a null reference")
    void nullReferenceAllowsDuplicates() {
        UUID walletId = givenWalletWithBalance("100.0000");

        // The unique index is PARTIAL (WHERE reference IS NOT NULL). Without
        // that clause Postgres treats NULLs as equal, so it would reject the
        // second row and make every unreferenced entry impossible.
        entries.saveAndFlush(new WalletEntry(UUID.randomUUID(), walletId,
                EntryType.DEPOSIT, new BigDecimal("1.0000"), new BigDecimal("1.0000"), null));
        entries.saveAndFlush(new WalletEntry(UUID.randomUUID(), walletId,
                EntryType.DEPOSIT, new BigDecimal("1.0000"), new BigDecimal("2.0000"), null));

        assertThat(entries.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a redelivered event is reported as already-handled, not applied twice")
    void duplicateEventIdIsIgnored() {
        UUID eventId = UUID.randomUUID();

        // 1 means this delivery was the first: apply the event.
        assertThat(processedEvents.insertIfAbsent(eventId, "UserCreated")).isEqualTo(1);

        // 0 means the envelope was already recorded, so the consumer returns
        // without touching its business tables. No exception, no second row.
        assertThat(processedEvents.insertIfAbsent(eventId, "UserCreated")).isZero();
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("NUMERIC(19,4) is exact where a double would drift")
    void moneyIsExact() {
        // Ten additions of 0.1 in double give 0.9999999999999999. In BigDecimal
        // the answer is exactly 1.
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < 10; i++) {
            running = running.add(new BigDecimal("0.1"));
        }
        assertThat(running).isEqualByComparingTo("1.0");

        UUID walletId = givenWalletWithBalance("0.0001");
        BigDecimal reloaded = wallets.findById(walletId).orElseThrow().getBalance();

        // Round-trips through NUMERIC without picking up binary error, and keeps
        // the four decimal places the column declares.
        assertThat(reloaded).isEqualByComparingTo("0.0001");
        assertThat(reloaded.scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("the CHECK constraint is what stops an overdraft, not application code")
    void checkConstraintIsTheLastLineOfDefence() {
        // Proves the rule lives in the database: bypass the entity entirely and
        // a raw UPDATE still cannot overdraw.
        UUID walletId = givenWalletWithBalance("100.0000");

        Assertions.assertThatThrownBy(() ->
                        jdbc.update("update wallets set balance = balance - 500 where id = ?", walletId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID givenWalletWithBalance(String balance) {
        Customer customer = customers.saveAndFlush(
                new Customer(UUID.randomUUID(), UUID.randomUUID(), "Ada", "USD"));
        Wallet wallet = wallets.saveAndFlush(
                new Wallet(UUID.randomUUID(), customer.getId(), "USD", new BigDecimal(balance)));
        return wallet.getId();
    }
}