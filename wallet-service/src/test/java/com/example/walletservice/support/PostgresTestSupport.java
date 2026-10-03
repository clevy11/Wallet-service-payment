package com.example.walletservice.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A single Postgres container shared by every integration test in this module.
 *
 * <p>Started from a static initialiser rather than as a per-class
 * {@code @Container} field, because a {@code @Container} field starts a fresh
 * container for each test class. This machine takes roughly half a minute to
 * start a JVM, so paying that per class is not affordable. Testcontainers'
 * Ryuk sidecar reaps the container when the JVM exits.
 *
 * <p>{@link ServiceConnection} is what makes the container's host, port and
 * credentials override the {@code spring.datasource.*} values in
 * application.yml, so tests never touch the developer's local walletdb.
 */
public abstract class PostgresTestSupport {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}