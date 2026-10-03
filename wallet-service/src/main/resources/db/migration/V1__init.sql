-- wallet-service schema. Owned exclusively by Flyway.
--
-- Money is NUMERIC, never float. NUMERIC is exact base-10 arithmetic: 0.1 is
-- representable exactly, whereas a double is binary floating point and accumulates
-- error over repeated arithmetic (0.1 added ten times gives 0.9999999999999999).
-- 19 digits total with 4 decimal places matches what payment rails can carry.

CREATE TABLE customers (
    id               UUID           PRIMARY KEY,
    -- owner_id is the JWT "sub" claim minted by auth-server. It is a plain column,
    -- NOT a foreign key: authdb and walletdb are separate databases and Postgres
    -- cannot enforce a constraint across them. The equality is an application-level
    -- promise, published as a UserCreated event on auth.events.
    --
    -- The UNIQUE constraint is the idempotency guard. When a duplicate UserCreated
    -- delivery arrives, exactly one INSERT wins and the loser gets a constraint
    -- violation. A SELECT-then-INSERT check would leave a race window between the
    -- two statements.
    owner_id         UUID           NOT NULL UNIQUE,
    display_name     VARCHAR(120)   NOT NULL,
    default_currency VARCHAR(3)     NOT NULL DEFAULT 'USD',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_customers_currency CHECK (default_currency ~ '^[A-Z]{3}$')
);

CREATE TABLE wallets (
    id          UUID           PRIMARY KEY,
    customer_id UUID           NOT NULL REFERENCES customers (id),
    currency    VARCHAR(3)     NOT NULL,
    balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status      VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- The overdraft rule is enforced by the database, not only by Java. Two
    -- concurrent withdrawals can both pass an "if (balance > amount)" check in
    -- application code; they cannot both satisfy this constraint.
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_wallets_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- A customer may hold several wallets (one per currency), but not two in the
-- same currency.
CREATE UNIQUE INDEX uq_wallets_customer_currency ON wallets (customer_id, currency);
CREATE INDEX idx_wallets_customer ON wallets (customer_id);

-- Append-only ledger. The balance in `wallets` is a cached running total; this
-- table is the audit trail that proves the two agree (sum of entries + opening
-- balance must equal wallets.balance). Rows are never updated or deleted.
CREATE TABLE wallet_entries (
    id            UUID           PRIMARY KEY,
    wallet_id     UUID           NOT NULL REFERENCES wallets (id),
    entry_type    VARCHAR(20)    NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    -- Caller-supplied idempotency key. The partial unique index makes a retried
    -- debit safe: the second attempt violates the constraint instead of charging
    -- twice. NULL is allowed because not every entry is externally initiated.
    reference     VARCHAR(64),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_wallet_entries_type CHECK (
        entry_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT', 'ADJUSTMENT')
    ),
    -- amount is always positive; direction is carried by entry_type. Storing a
    -- negative amount alongside a type that already implies direction invites
    -- double-negation bugs at the call sites.
    CONSTRAINT chk_wallet_entries_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_wallet_entries_wallet ON wallet_entries (wallet_id, created_at DESC);
CREATE UNIQUE INDEX uq_wallet_entries_reference ON wallet_entries (reference)
    WHERE reference IS NOT NULL;

-- Event-level dedup. UNIQUE(owner_id) on customers handles "set this state"
-- events, where replaying converges on the same result. It cannot help a relative
-- operation: replaying "debit 50" twice must not charge twice, and two legitimate
-- debits of 50 are indistinguishable by business key. So ledger-affecting events
-- are deduped on the envelope's eventId here instead.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);