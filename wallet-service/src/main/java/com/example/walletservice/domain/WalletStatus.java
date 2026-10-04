package com.example.walletservice.domain;

/**
 * Lifecycle of a wallet. Deliberately a closed set: the database has a CHECK
 * constraint listing exactly these values, so an unknown status is a startup
 * failure rather than a row nobody can interpret.
 */
public enum WalletStatus {
    ACTIVE,
    FROZEN,
    CLOSED;

    /**
     * Whether money may move in or out.
     *
     * <p>Not {@code this == ACTIVE} at the call site, because the two questions are
     * different: FROZEN blocks a debit but must still allow a refund in, otherwise a
     * disputed charge could never be returned to a frozen account. Expressed as a
     * method so that decision is made once, here, where it can be read.
     *
     * <p>Today this returns false for everything but ACTIVE, and the SQL predicates in
     * {@code WalletRepository} agree. It exists as a named concept so that widening
     * FROZEN later is a one-line change with an obvious blast radius, rather than a
     * search for {@code == ACTIVE} across the codebase.
     */
    public boolean isTransactable() {
        return this == ACTIVE;
    }
}