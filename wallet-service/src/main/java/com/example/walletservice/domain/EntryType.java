package com.example.walletservice.domain;

/**
 * Direction and cause of a ledger movement.
 *
 * <p>{@code amount} is always positive; the direction is carried entirely by this
 * type. Allowing negative amounts here would mean every call site has to remember
 * not to negate an already-negative value, and one that forgets silently reverses
 * a transfer.
 */
public enum EntryType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_IN,
    TRANSFER_OUT,
    ADJUSTMENT
}