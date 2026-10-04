-- A ledger entry now names the transfer it belongs to.
--
-- Without this, a client that retries with the same Idempotency-Key finds the entry
-- that proves the transfer already happened but cannot learn the original transfer
-- id, because the id only existed in the outbox row and nothing links the two. The
-- caller would be told "this was already done" and given no handle for it, which
-- makes the replay response useless for reconciliation.
--
-- Nullable, because not every entry belongs to a transfer: a DEPOSIT or an ADJUSTMENT
-- has no counterparty transfer. A NOT NULL column would be a lie about how the ledger
-- is used.

ALTER TABLE wallet_entries
    ADD COLUMN transfer_id UUID;

-- Reading one transfer's legs back, for reconciliation or dispute handling.
CREATE INDEX idx_wallet_entries_transfer ON wallet_entries (transfer_id)
    WHERE transfer_id IS NOT NULL;
