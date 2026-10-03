# AGENTS.md

## What this repo is right now
A bare Spring Initializr skeleton — **no business logic exists yet**. The only tracked
source is `WalletServiceApplication` + `WalletServiceApplicationTests`. No README, no
docker-compose, no CI. Never assume a feature is already implemented.

It is also a **learning project**: the owner is refreshing distributed-systems knowledge.
Read "Working style" before writing any code.

## Build & test
- Use `./mvnw`, not system `mvn` (system Maven is 3.9.6; the wrapper pins 3.9.16).
- The wrapper is `distributionType=only-script`: it downloads Maven on first run and needs
  no `maven-wrapper.jar`. `.gitignore` ignores that jar on purpose — don't restore it.
- `mvnw` was committed to git **without the execute bit** (mode 100644), so a fresh clone
  failed with `permission denied`. Fixed via `git update-index --chmod=+x mvnw` — keep it
  executable; scripts committed from an archive often lose the bit.
- The build is a **7-project reactor** (root aggregator + 6 services):
  - `./mvnw clean package` — everything
  - `./mvnw -pl wallet-service test` — one module
  - `./mvnw spring-boot:run -pl wallet-service`
  - `./mvnw test -Dtest=WalletServiceApplicationTests`
- POM inheritance is resolved at build time from disk via `<relativePath>`, so Docker
  builds must COPY the root `pom.xml` too — not just the module's pom.
- No `.mvn/maven.config` or `jvm.config`: don't assume custom flags or offline mode.
- No lint, formatter, or typecheck task exists (no Spotless/Checkstyle/EditorConfig).
  Don't add one uninvited; match the surrounding 4-space style.

## Java / toolchain traps
- `pom.xml` sets `<java.version>21</java.version>`, but `.idea/misc.xml` pins
  `openjdk-24` / `JDK_24`. Only JDK 21 is installed. Trust the pom; the IDE SDK is stale
  and shows errors that aren't real.
- Lombok processing is wired by hand in `pom.xml` via `<annotationProcessorPaths>` on both
  `default-compile` and `default-testCompile`. Because the list is explicit, **any new
  annotation processor must be added to both lists** or it silently won't run.
- Boot 4 modularized the test starters: this pom uses `spring-boot-starter-data-jpa-test`
  and `spring-boot-starter-security-test`, not `spring-boot-starter-test`.
- **Test starters are not lightweight.** `spring-boot-starter-data-jpa-test` transitively
  pulls in the production JPA starter, so a plain `@SpringBootTest` boots Hibernate and
  demands a DataSource ("Failed to determine a suitable driver class"). Only add a tech
  test starter once that technology is really in the module.
- Boot 4 renamed `spring-boot-starter-aop` to **`spring-boot-starter-aspectj`** (the old
  coordinates 404). Needed for annotation-driven Resilience4j aspects.

## Schema ownership — Flyway, never by hand
- **Never create or alter tables manually in `psql`.** Flyway migrations are the only
  source of truth for schema. Hand-creating a table makes `V*__init.sql` fail with
  "relation already exists", and "fixing" it with a Flyway baseline is worse: Flyway then
  skips V1 and the database shape can never be reproduced by anyone else.
- To correct a mistake, add a **new** migration (`V2__...`). Never edit the live DB.
- Split of ownership: the **databases** (`walletdb`, `paymentdb`) are created by the
  Postgres container's init script in `infra/`; everything *inside* them is Flyway's.

## Version strategy — do not "helpfully" upgrade
- `pom.xml` currently declares Spring Boot **4.1.1**. The agreed target is **Boot 4.0.1 +
  Spring Cloud 2025.1.3** ("Oakwood"), because **no Spring Cloud GA train supports Boot
  4.1.x** — 2025.1 targets Boot 4.0.x and the next train is only 2026.0.0-M1. The move to
  4.0.1 is deliberate; preserve it.
- Boot 4 consequences: springdoc must be **v3.x**; Resilience4j needs
  `resilience4j-spring-boot4`; Testcontainers is **2.x**, whose Java API differs from the
  1.x tutorials online — verify against the actual jar before copying snippets; Jackson 3,
  not Jackson 2.
- **Testcontainers 2.x renamed every module with a `testcontainers-` prefix.** The 1.x
  names are frozen at 1.21.4 forever, so a copy-pasted 1.x coordinate resolves against the
  old artifact and never sees the module. Correct 2.0.3 coordinates:
  `org.testcontainers:testcontainers-junit-jupiter`,
  `org.testcontainers:testcontainers-postgresql`,
  `org.testcontainers:testcontainers-kafka`. Java packages are unchanged
  (`org.testcontainers.postgresql.PostgreSQLContainer`,
  `org.testcontainers.kafka.KafkaContainer`, `org.testcontainers.junit.jupiter.*`).
  2.x also adds `@EnabledIfDockerAvailable`.
- **Resilience4j 2.4.0 split annotations into their own artifact**
  `io.github.resilience4j:resilience4j-annotations` — `resilience4j-circuitbreaker` alone
  no longer contains `@CircuitBreaker`. It publishes `-spring-boot3` / `-spring-boot4` /
  `-spring-boot6` variants; Boot 4 uses `-spring-boot4`. Config roots:
  `resilience4j.{circuitbreaker,retry,timelimiter,bulkhead}.instances.<name>.<setting>`.

## Environment requirements
- **The Docker daemon is usually not running.** Start Docker Desktop before any
  Testcontainers or `docker compose` work, otherwise integration tests fail on container
  startup instead of on logic. (Docker Desktop 4.41.2 / engine 28.1.1, 8.3 GB to Docker.)
- `timeout(1)` is not installed on this Mac — don't use it in shell commands.
- Host is an Intel (x86_64) 2016 MacBook Pro, 16 GB RAM / 8 CPU. Don't plan to run Kafka +
  Postgres + Keycloak + Grafana + 5 JVM services at once: cap heaps (`-Xmx256m`) and keep
  observability/auth infrastructure behind opt-in compose profiles.
- A service JVM takes ~30-40s to start on this machine; budget for it in verification.

## Local infra (`docker-compose.yml`)
- `docker compose up -d` starts Postgres only; `--profile kafka up -d` adds the broker.
  Kafka is behind a profile because it is not needed until Day 3 and Docker only has
  8 GB here. `docker compose config --profiles` lists available profiles.
- **Postgres is published on host port 5433, not 5432** — 5432 is occupied by a leftover
  process from the pre-upgrade Docker 20.10 install. The container still speaks 5432
  internally, so connect from the Mac as `localhost:5433` but from another container on
  the compose network as `postgres:5432`. pgAdmin credentials: postgres / postgres.
- `docker compose down` **keeps** data; `down -v` deletes the volume, which re-triggers
  the init scripts on the next start. Don't reach for `-v` casually.
- The init script runs **only on first initialisation of an empty data directory**. After
  editing `infra/initdb/*.sql` you must `down -v` for it to take effect.
- CLI alternative to pgAdmin: `docker exec -it wallet-postgres psql -U postgres -d walletdb`.
- `postgres/postgres` is committed in `docker-compose.yml` on purpose — local dev only.
  Never reuse these credentials anywhere real.

## Git
- One branch per feature slice: `feat/<slice-id>-<short-name>`; one commit per slice;
  conventional-commit messages; tag each completed day `day1`..`day5`.
- `.idea/` and `target/` are ignored but **`.DS_Store` is not** — `git status` always shows
  untracked `.DS_Store` files. Expected; never commit them.
- `main` is the only local branch. `origin` is `github.com/clevy11/Wallet-service-payment.git`
  (a `master` branch also exists on the remote). Commit only when explicitly asked.

## Target architecture (not implemented yet)
Multi-module Maven with the root pom as aggregator: `wallet-service` (8081),
`payment-service` (8082), `bank-mock` (8083), `notification-service` (8084),
`api-gateway` (8080), `auth-server` (JWT issuer).

Settled decisions — don't relitigate or substitute alternatives:
- **Database per service.** No cross-service queries or joins, ever.
- **No shared `common` module.** Each service owns its event contracts; duplication between
  producer and consumer is intentional, not an oversight.
- Topics are `wallet.events` / `payment.events` with a typed envelope (eventId, eventType,
  aggregateId, version, occurredAt, correlationId, payload) — not one topic per event type.
- Idempotency keys, transactional outbox, and a choreographed saga that compensates on
  `payment.failed`.
- Auth is a self-issued JWT module (`auth-server`: Nimbus + RSA keypair + JWKS). Keycloak
  is an optional compose profile only.
- **No service discovery.** No Eureka, no Consul. Every service address is a config
  value (`spring.cloud.gateway.routes[].uri`, `bank.base-url`,
  `spring.kafka.bootstrap-servers`). Don't "improve" this by adding a registry.
- The gateway fronts `wallet-service` only; `payment-service` is internal.
- wallet-service and payment-service never call each other directly — they meet only
  through Kafka. So there is no discovery problem across that boundary.
- Money is `BigDecimal`, never `double`. Java 21, records for DTOs, **no Lombok**.

## Working style (important)
- This repo exists to teach distributed systems, not to ship code fast. Deliver **one
  feature slice at a time**; never scaffold the whole system in a single pass.
- Before implementing any distributed mechanic — idempotency, transactional outbox, Kafka
  partition/offset/consumer-group semantics, saga compensation, circuit-breaker/retry
  behaviour, service-to-service token propagation, trace-context propagation — explain the
  concept and its trade-offs first, then code it.
- The owner is already fluent in Java and Spring basics; skip generic Java tutorial
  material.