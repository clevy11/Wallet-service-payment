package com.example.authserver.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A single Postgres container shared by every integration test in this module.
 *
 * <p>Static initialiser rather than a per-class {@code @Container} field, matching
 * wallet-service: a {@code @Container} field starts a fresh container per test
 * class, and a JVM start on this machine costs roughly half a minute.
 * Testcontainers' Ryuk sidecar reaps the container when the JVM exits.
 *
 * <p>{@link ServiceConnection} overrides {@code spring.datasource.*} from
 * application.yml, so tests never touch the developer's local authdb.
 */
public abstract class PostgresTestSupport {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}