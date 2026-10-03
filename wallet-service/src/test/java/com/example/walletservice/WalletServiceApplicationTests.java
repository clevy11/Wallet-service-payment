package com.example.walletservice;

import com.example.walletservice.support.PostgresIntegrationTest;

import org.junit.jupiter.api.Test;

/**
 * Context-loads smoke test.
 *
 * <p>Extends {@link PostgresIntegrationTest} rather than using a bare
 * {@code @SpringBootTest}, because from this slice on the context needs a
 * DataSource. Running it against the container means the build never depends on
 * a developer's local Postgres being up, and never mutates their walletdb.
 */
class WalletServiceApplicationTests extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
    }
}