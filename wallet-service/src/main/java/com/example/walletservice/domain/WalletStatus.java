package com.example.walletservice.domain;

/**
 * Lifecycle of a wallet. Deliberately a closed set: the database has a CHECK
 * constraint listing exactly these values, so an unknown status is a startup
 * failure rather than a row nobody can interpret.
 */
public enum WalletStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}