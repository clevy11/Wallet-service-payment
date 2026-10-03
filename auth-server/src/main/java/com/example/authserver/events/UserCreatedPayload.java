package com.example.authserver.events;

/**
 * Body of a {@code UserCreated} event.
 *
 * <p>{@code userId} is auth-server's user id and becomes the JWT {@code sub}
 * claim. wallet-service copies it straight into {@code customers.owner_id}, which
 * is how the same human is recognised in two databases without either service
 * querying the other.
 *
 * <p>Carrying email here is worth a moment's thought. It means a replay
 * re-derives the same {@code userId} (auth generated it), so wallet-service's
 * {@code UNIQUE (owner_id)} settles the duplicate and it does not need an
 * {@code eventId} guard. That works only because creating a customer is a
 * <em>state-setting</em> operation, where replaying converges. Day 3's "debit
 * 50" is a relative operation and needs the eventId guard instead.
 */
public record UserCreatedPayload(
        String userId,
        String username,
        String email) {
}