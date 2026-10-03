package com.example.authserver.support;

import com.example.authserver.repository.OutboxEventRepository;
import com.example.authserver.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base class for auth-server tests that need a real Postgres.
 *
 * <p>Deliberately not {@code @Transactional}, for the same reason as
 * wallet-service's equivalent: a rollback-per-test would hide what these tests are
 * here to prove. Specifically, a failed insert inside a test-managed transaction
 * only marks it rollback-only -- it does not stop the code under test from
 * continuing and attempting more writes. The duplicate-registration case is the
 * interesting one, and it needs a real commit to behave like production.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest extends PostgresTestSupport {

    @Autowired
    protected UserRepository users;

    @Autowired
    protected OutboxEventRepository outbox;

    @BeforeEach
    void resetDatabase() {
        outbox.deleteAllInBatch();
        users.deleteAllInBatch();
    }
}