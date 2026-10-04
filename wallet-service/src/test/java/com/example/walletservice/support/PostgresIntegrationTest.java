package com.example.walletservice.support;

import com.example.walletservice.repository.CustomerRepository;
import com.example.walletservice.repository.OutboxEventRepository;
import com.example.walletservice.repository.ProcessedEventRepository;
import com.example.walletservice.repository.WalletEntryRepository;
import com.example.walletservice.repository.WalletRepository;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base class for tests that need a real Postgres.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional}. Wrapping each test in a
 * transaction and rolling it back is the usual shortcut, but it would hide the
 * behaviour these tests exist to prove: a constraint violation only marks the
 * ambient transaction rollback-only, and a committed write is what a duplicate
 * delivery actually looks like. So the tests clean up explicitly instead.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest extends PostgresTestSupport {

    @Autowired
    protected CustomerRepository customers;

    @Autowired
    protected WalletRepository wallets;

    @Autowired
    protected WalletEntryRepository entries;

    @Autowired
    protected ProcessedEventRepository processedEvents;

    @Autowired
    protected OutboxEventRepository outbox;

    /** Delete in FK-safe order: children before parents. */
    @BeforeEach
    void resetDatabase() {
        entries.deleteAllInBatch();
        outbox.deleteAllInBatch();
        wallets.deleteAllInBatch();
        customers.deleteAllInBatch();
        processedEvents.deleteAllInBatch();
    }
}