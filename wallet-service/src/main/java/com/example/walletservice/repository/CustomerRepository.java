package com.example.walletservice.repository;

import java.util.Optional;
import java.util.UUID;

import com.example.walletservice.domain.Customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByOwnerId(UUID ownerId);

    /**
     * Creates the customer, or does nothing if one already exists for this owner.
     *
     * <p>Written as a single {@code INSERT ... ON CONFLICT DO NOTHING} rather than
     * a {@code findByOwnerId} check followed by a {@code save}. The two-statement
     * version leaves a window between the SELECT and the INSERT in which a
     * concurrent delivery of the same event sees no customer and inserts its own.
     * Both then commit, and the person has two customer rows.
     *
     * <p>With one statement the database arbitrates: the first delivery inserts, the
     * second matches nothing and affects zero rows. There is no window to lose.
     *
     * <p>Deliberately no exception on conflict. A duplicate is an expected,
     * routine outcome of at-least-once delivery, not an error -- treating it as one
     * would mean every redelivery logged a failure and any retry policy would keep
     * re-handling the same harmless message.
     *
     * @return 1 if this caller created the row, 0 if it already existed
     */
    @Modifying
    @Transactional
    @Query(value = """
            insert into customers (id, owner_id, display_name, default_currency, created_at)
            values (:id, :ownerId, :displayName, 'USD', now())
            on conflict (owner_id) do nothing
            """, nativeQuery = true)
    int createIfAbsent(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
                       @Param("displayName") String displayName);
}