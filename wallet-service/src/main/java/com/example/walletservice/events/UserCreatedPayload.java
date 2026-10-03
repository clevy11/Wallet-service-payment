package com.example.walletservice.events;

/**
 * wallet-service's copy of the {@code UserCreated} body.
 *
 * <p>{@code userId} is auth-server's user id, carried straight into
 * {@code customers.owner_id}. Nothing queries authdb to find it -- the value
 * travels in the event, which is the whole point of decoupling the databases.
 */
public record UserCreatedPayload(
        String userId,
        String username,
        String email) {
}