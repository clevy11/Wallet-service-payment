# AGENTS.md

## What this repo is right now
Six-module Spring Boot 4.0.1 microservice skeleton, the wallet domain schema, and the
`UserCreated` event path end to end: `POST /auth/register` → transactional outbox → relay →
`auth.events` → wallet-service consumer → customer row. `payment-service`, `bank-mock`,
`notification-service` and `api-gateway` are still empty skeletons, and no service issues
a JWT yet. Never assume a feature is already implemented; see "Architecture (target)" for
the per-module status list.

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
- **Docker must be running for the wallet-service build.** Its tests are Testcontainers
  integration tests, so `./mvnw clean package` fails on container startup, not on logic,
  if the daemon is down.

## Java / toolchain traps
- `pom.xml` sets `<java.version>21</java.version>`, but `.idea/misc.xml` pins
  `openjdk-24` / `JDK_24`. Only JDK 21 is installed. Trust the pom; the IDE SDK is stale
  and shows errors that aren't real.
- Lombok is a single `provided` dependency declared in the root `pom.xml`, so every module
  inherits it. This pom declares **no** `<annotationProcessorPaths>`, so processors are
  discovered from the compile classpath automatically — the "add every processor to both
  `default-compile` and `default-testCompile` lists" rule that applied to the original
  skeleton no longer applies here. Don't reintroduce explicit lists.
- **Entities use `@Getter @Setter @NoArgsConstructor(PROTECTED)`, never `@Data`.** `@Data`
  expands to `@ToString` + `@EqualsAndHashCode` over every field, which is wrong for a
  mutable JPA entity: the generated `hashCode` includes mutable state and the `@Version`
  counter, so it changes the moment the row is saved and corrupts any `HashSet` or map key
  holding the entity. Hibernate treats the *identifier* as identity, not field equality.
  `@Data` is safe only on DTOs, where fields are immutable.
- `@Setter(AccessLevel.NONE)` on `id`, `owner_id` and `@Version` fields: identity is assigned
  at construction and must not be reassignable by application code. Use `javap -p
  target/classes/...` to confirm the generated surface — Lombok's output is invisible in
  the source.
- Boot 4 modularized the test starters: this pom uses `spring-boot-starter-data-jpa-test`
  and `spring-boot-starter-security-test`, not `spring-boot-starter-test`.
- **Test starters are not lightweight.** `spring-boot-starter-data-jpa-test` transitively
  pulls in the production JPA starter, so a plain `@SpringBootTest` boots Hibernate and
  demands a DataSource ("Failed to determine a suitable driver class"). Only add a tech
  test starter once that technology is really in the module.
- Boot 4 renamed `spring-boot-starter-aop` to **`spring-boot-starter-aspectj`** (the old
  coordinates 404). Needed for annotation-driven Resilience4j aspects.
- **Flyway needs its dialect artifact declared separately.** Since Flyway 10 the database
  support is not in `flyway-core`, so `spring-boot-starter-flyway` alone boots, fails to
  find a PostgreSQL plugin, and dies. Add
  `org.flywaydb:flyway-database-postgresql` as well.
- **`@ServiceConnection` needs `org.springframework.boot:spring-boot-testcontainers`.** The
  Testcontainers 2.x modules do not pull it in transitively, and without it the annotation
  does not exist to import.
- **A manually assigned `@Id` makes `save()` call `merge()`, not `persist()`.** `merge`
  SELECTs first, so re-saving an existing row issues an UPDATE and silently upserts instead
  of colliding with the primary key. This defeats idempotency guards that assume an
  INSERT will fail. Use `INSERT ... ON CONFLICT DO NOTHING` for dedup
  (see `ProcessedEventRepository.insertIfAbsent`) and assert on the returned row count.
- **Boot 4 is Jackson 3, so the package is `tools.jackson`, not `com.fasterxml.jackson`.**
  Autoconfigured `ObjectMapper` is `tools.jackson.databind.ObjectMapper`; injecting
  `com.fasterxml...ObjectMapper` fails with "required a bean of type ... could not be found".
  `JacksonException` is a **RuntimeException** in Jackson 3, so `writeValueAsString` /
  `readTree` no longer need checked-exception handling. Jackson 2 is still on the classpath
  transitively (spring-security-oauth2-jose, Flyway) — that is not a signal to use it.
- **Spring Kafka 4 ships two parallel serializer families and only one is Jackson 3.**
  `JsonSerializer` / `JsonDeserializer` are Jackson 2 (`com.fasterxml`); `JacksonJsonSerializer`
  / `JacksonJsonDeserializer` are Jackson 3 (`tools.jackson`). Boot 4 configures the Jackson 3
  `ObjectMapper`, so use the `Jackson*` pair. Mismatching them does not fail at boot — the
  payload just arrives unparsed or unserialisable at runtime.
- **`@KafkaListener` has no `ackMode` attribute** (verified with `javap`). Ack mode is a
  container-factory setting: configure `spring.kafka.listener.ack-mode` (with
  `spring.kafka.consumer.enable-auto-commit: false`).
- **`ConsumerTestUtils` is gone in Spring Kafka 4** — it is `KafkaTestUtils`
  (`org.springframework.kafka.test.utils.KafkaTestUtils`). Same for `@EmbeddedKafka`, which
  is KRaft-based and needs no ZooKeeper. A `FileNotFoundException` on
  `.../combined_0_0/replication-offset-checkpoint.tmp` during teardown is harmless noise.
- **`@EmbeddedKafka` tests must not share one topic.** Records outlive a test method, and a
  fresh consumer group reads from `earliest`, so the second test sees the first test's
  messages and fails with "More than one record for topic found". Create a uniquely named
  topic per test (AdminClient) and build the relay against it. Also park the schedule in
  tests via `app.outbox.*-ms=600000`, or the `@Scheduled` relay publishes rows mid-assertion
  and failures become intermittent.
- **`TestPropertySource` is `org.springframework.test.context.TestPropertySource`**, not
  `org.springframework.boot.test.context` (Boot 4 moved/removed the latter).
- **A self-invoked `@Transactional` method silently runs with no transaction.** Spring's
  proxy is bypassed when a bean calls its own method, so `@Transactional(REQUIRES_NEW)`
  inside `OutboxRelay` was inert — and invisible, because `saveAndFlush` opens an implicit
  transaction from the repository anyway. Keep transactional boundaries on a *separate bean*
  (`OutboxMarker`). The same rule is why `UserRegistrationService` is an interface.

## Schema ownership — Flyway, never by hand
- **Never create or alter tables manually in `psql`.** Flyway migrations are the only
  source of truth for schema. Hand-creating a table makes `V*__init.sql` fail with
  "relation already exists", and "fixing" it with a Flyway baseline is worse: Flyway then
  skips V1 and the database shape can never be reproduced by anyone else.
- To correct a mistake, add a **new** migration (`V2__...`). Never edit the live DB.
- Split of ownership: the **databases** (`walletdb`, `paymentdb`, `authdb`) are created by
  the Postgres container's init script in `infra/`; everything *inside* them is Flyway's.
- **No cross-database foreign keys, ever.** The link from a wallet-service customer to an
  auth-server user is `customers.owner_id` = the JWT `sub` claim, a plain column. Postgres
  cannot enforce a constraint across databases, so the equality is an application-level
  promise established by a `UserCreated` event on `auth.events`.

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
- The embedded `@EmbeddedKafka` broker used by `OutboxRelayTests` needs no ZooKeeper and no
  Docker, but it does need ~350 MB of heap. It is the only test in the repo that starts a
  broker, so keep it in one class rather than spreading it.

## Local infra (`docker-compose.yml`)
- `docker compose up -d` starts Postgres only; `--profile kafka up -d` adds the broker
  **and** Redpanda Console. Kafka is behind a profile because it is not needed until Day 3
  and Docker only has 8 GB here. `docker compose config --profiles` lists available profiles.
- **Redpanda Console (Kafka GUI) is at http://localhost:18080** — a viewer for the broker
  only; the broker stays stock Apache Kafka. Host port 18080, not 8080, because 8080 is
  api-gateway's. It reads the broker over the in-network listener (`kafka:29092`), so
  changing advertised listeners means restarting it too.
- **Kafka has two advertised listeners and both are needed:** `localhost:9092` for services
  run from the Mac, `kafka:29092` for containers on the compose network. A broker advertises
  one address per listener, and a client that resolved `kafka:29092` is *handed* the
  advertised address for the rest of the connection — so a container pointing at
  `localhost:9092` dials itself. Before containerising a service, do not just give it
  `kafka:9092`; use the INTERNAL listener.
- **`KAFKA_LOG_DIRS` must be set explicitly.** The `apache/kafka` image's bundled
  `server.properties` points `log.dirs` at `/tmp/kraft-combined-logs`, so the mounted
  `kafka-data` volume sits empty and every broker recreate loses all topics *and* consumer
  group offsets. Fixed to `/var/lib/kafka/data`; if topics vanish after a restart, check
  this first.
- `auth.events` exists with one partition. Topic records outlive every test and every
  experiment, so a hand-made message can poison the consumer long after it was sent
  (see the partition-blocking note under Architecture). Recreate the topic to clear it.
- **Postgres is published on host port 5433, not 5432** — 5432 is occupied by a leftover
  process from the pre-upgrade Docker 20.10 install. The container still speaks 5432
  internally, so connect from the Mac as `localhost:5433` but from another container on
  the compose network as `postgres:5432`. pgAdmin credentials: postgres / postgres.
- `docker compose down` **keeps** data (both the Postgres and Kafka volumes, now that
  `KAFKA_LOG_DIRS` is set); `down -v` deletes them, which re-triggers the init scripts on
  the next start. Don't reach for `-v` casually.
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
- `origin` is `github.com/clevy11/Wallet-service-payment.git`. **Push uses HTTPS with a
  token already stored in the macOS keychain** (git credential helper `osxkeychain`), so
  pushes do not prompt. The `~/.ssh/id_ed25519` key is *not* registered with GitHub —
  SSH push fails with `Permission denied (publickey)`, so don't switch the remote to SSH.
- `gh` CLI is not installed.
- `origin/master` is a stale branch (two commits behind `main`, and missing LICENSE).
  `main` is the branch that matters.
- **Never force-push.**
- **Workflow: branch → push → PR → merge.** Push the feature branch, open the PR against
  `main`, then merge. Do **not** fast-forward `main` locally first: once `main` contains the
  branch's commits, GitHub reports "There isn't anything to compare" and refuses to open a
  PR. Steps: `git switch -c feat/<id>-<name>` → work → `git add -A -- . ':!*.DS_Store'` →
  commit → `git push -u origin HEAD` → open the PR URL GitHub prints.

## Architecture (target)
Multi-module Maven with the root pom as aggregator: `wallet-service` (8081),
`payment-service` (8082), `bank-mock` (8083), `notification-service` (8084),
`api-gateway` (8080), `auth-server` (8090, JWT issuer, **owns `authdb`**).

What exists today, so nobody assumes a feature is already there:
- `wallet-service`: Flyway V1 (`customers`, `wallets`, `wallet_entries`,
  `processed_events`), JPA entities and repositories, and a `UserCreated` consumer on
  `auth.events`. No HTTP endpoints yet.
- `auth-server`: Flyway V1 (`users`, `outbox_events`), `POST /auth/register`, and the
  outbox relay publishing to `auth.events`. **No JWT is issued yet** — `password_hash`
  holds a `NOT-A-HASH:` placeholder until the login slice replaces it with BCrypt.
- `payment-service`, `bank-mock`, `notification-service`, `api-gateway`: skeleton only.

Settled decisions — don't relitigate or substitute alternatives:
- **Database per service.** No cross-service queries or joins, ever.
- **`auth-server` is stateful and owns `authdb`.** Verifying a password means looking the
  user up, so "stateless" auth is not an option here. `authdb` holds credentials, roles,
  `token_version` and refresh tokens **only** — never wallet or ledger data. Bump
  `token_version` to invalidate every issued token for a user instantly.
- **No shared `common` module.** Each service owns its event contracts; duplication between
  producer and consumer is intentional, not an oversight.
- Topics are `auth.events` / `wallet.events` / `payment.events` with a typed envelope
  (eventId, eventType, aggregateId, version, occurredAt, correlationId, payload) — **not one
  topic per event type**, so a new event type needs no consumer reconfiguration.
- **`correlationId` is captured at write time** from the request (`X-Correlation-Id`), not
  generated by the publisher — generated at publish time it traces nothing.
- **Every event is keyed by `aggregateId` when published.** Same partition means same
  aggregate is processed by one consumer at a time, in order.
- **Idempotency key = the column whose uniqueness you enforce.** `UNIQUE(owner_id)` protects
  state-setting events (replay converges); `processed_events(event_id)` protects relative
  ones like "debit 50", where no business key can distinguish a replay from a legitimate
  repeat. Both are needed.
- **Transactional outbox, then the relay.** Business row and outbox row commit in one
  transaction; a `@Scheduled` publisher relays `published_at IS NULL` rows. This removes
  event *loss* but not *duplicates* — which is precisely why consumers must be idempotent.
  Never write to the DB and Kafka in the same `@Transactional` method (the dual-write bug).
  The relay must block on `send().get()`: `send()` is asynchronous, so marking a row
  published without waiting for the broker turns a retryable failure into silent,
  permanent event loss.
- **A determinately-failing consumer record blocks its partition forever.** Observed for
  real: a malformed `UserCreated` payload violated `customers.display_name NOT NULL`, the
  handler threw, the offset never advanced, and every later event on that partition waited
  behind it. Current behaviour is deliberate — retry, lose nothing — but it is unbounded.
  Bounded retries plus a dead-letter topic is the fix, and it is not built yet.
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
- Money is `BigDecimal`, never `double`. Java 21, records for DTOs. Lombok is available
  and used on JPA entities (`@Getter @Setter @NoArgsConstructor(PROTECTED)`), but DTOs stay
  records — see "Java / toolchain traps" for why `@Data` is banned on entities.

## Working style (important)
- This repo exists to teach distributed systems, not to ship code fast. Deliver **one
  feature slice at a time**; never scaffold the whole system in a single pass.
- Before implementing any distributed mechanic — idempotency, transactional outbox, Kafka
  partition/offset/consumer-group semantics, saga compensation, circuit-breaker/retry
  behaviour, service-to-service token propagation, trace-context propagation — explain the
  concept and its trade-offs first, then code it.
- The owner is already fluent in Java and Spring basics; skip generic Java tutorial
  material.