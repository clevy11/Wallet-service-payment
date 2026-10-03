-- Runs once, only when the Postgres data directory is first initialised.
--
-- Ownership split: this script creates DATABASES only. Everything inside a
-- database (tables, indexes, constraints) is owned by Flyway and must never be
-- created by hand -- see AGENTS.md.
--
-- Note: PostgreSQL has no "CREATE DATABASE IF NOT EXISTS" (that is MySQL syntax and
-- fails with a syntax error, which aborts the whole file under ON_ERROR_STOP).
-- Conditional logic is unnecessary anyway: this script only ever runs once, against
-- a freshly initialised, empty data directory.


CREATE DATABASE walletdb;
CREATE DATABASE paymentdb;