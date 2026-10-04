package com.example.walletservice.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByCustomerId(UUID customerId);

    List<Wallet> findByCustomerIdAndStatus(UUID customerId, WalletStatus status);

    Optional<Wallet> findByCustomerIdAndCurrency(UUID customerId, String currency);

    /**
     * Creates a wallet, or does nothing if this customer already has one in this
     * currency.
     *
     * <p>One statement against {@code uq_wallets_customer_currency}, exactly as
     * {@code CustomerRepository.createIfAbsent} does for customers. A
     * {@code findByCustomerIdAndCurrency} check followed by an insert has a window
     * between the SELECT and the INSERT in which two concurrent requests both see
     * nothing and both insert; one then dies on the constraint as a 500 rather than
     * returning the wallet that already exists.
     *
     * <p>No exception on conflict. Re-creating an existing wallet is a normal client
     * behaviour (a retry, or a user who forgot they had one), not an error.
     *
     * @return 1 if this caller created the row, 0 if it already existed
     */
    @Modifying
    @Query(value = """
            insert into wallets (id, customer_id, currency, balance, status, version, created_at, updated_at)
            values (:id, :customerId, :currency, 0, 'ACTIVE', 0, now(), now())
            on conflict (customer_id, currency) do nothing
            """, nativeQuery = true)
    int createIfAbsent(@Param("id") UUID id, @Param("customerId") UUID customerId,
                       @Param("currency") String currency);

    /**
     * Moves money out of a wallet, but only if it is there.
     *
     * <p>This single statement is the concurrency control. The obvious Java
     * alternative -- read the balance, check it in Java, write it back -- has a window
     * between the read and the write in which every concurrent withdrawal sees the
     * same balance and every one of them decides it can afford its amount. Two
     * withdrawals of 80 from a balance of 100 both pass the check and together take
     * 160. The application-level check is not merely insufficient, it is worse than
     * useless: it looks like protection.
     *
     * <p>Moving the test into the WHERE clause closes the window. Postgres takes a
     * row lock for the duration of the UPDATE and re-evaluates the predicate against
     * the committed row, so the second concurrent withdrawal updates 0 rows and is
     * told the money is not there. The caller learns that from the row count, not
     * from a constraint violation or a lost update.
     *
     * <p>{@code chk_wallets_balance_non_negative} is the backstop for anything that
     * reaches the table by another route. It cannot be satisfied by two concurrent
     * overdraws even if this method were bypassed entirely.
     *
     * <p>Bypasses {@code @Version} deliberately: an atomic conditional UPDATE is the
     * mechanism, and JPA's optimistic counter would only add a second, weaker one
     * that has to be retried. Nothing here loads the entity, so there is no version
     * to bump -- the row's {@code version} column is left untouched by transfers.
     *
     * @return 1 if the money moved, 0 if the balance was insufficient
     */
    @Modifying
    @Query(value = """
            update wallets
               set balance = balance - :amount, updated_at = now()
             where id = :walletId
               and status = 'ACTIVE'
               and balance >= :amount
            """, nativeQuery = true)
    int debitIfSufficient(@Param("walletId") UUID walletId, @Param("amount") BigDecimal amount);

    /**
     * The other leg. No predicate on the balance: money coming in cannot overdraw,
     * and a conditional UPDATE here would only add a failure mode. The status check
     * is kept so a closed wallet cannot silently start accruing.
     *
     * @return 1 if credited, 0 if the wallet is not accepting money
     */
    @Modifying
    @Query(value = """
            update wallets
               set balance = balance + :amount, updated_at = now()
             where id = :walletId
               and status = 'ACTIVE'
            """, nativeQuery = true)
    int credit(@Param("walletId") UUID walletId, @Param("amount") BigDecimal amount);

    /**
     * Reads the balance straight after a conditional UPDATE, inside the same
     * transaction, so {@code balance_after} on the ledger entry is exact.
     *
     * <p>A projection rather than {@code findById} because the entity is no longer
     * managed by this point -- the UPDATE bypassed the persistence context, so the
     * cached entity would still hold the pre-update balance and quietly write the
     * wrong {@code balance_after} into the audit trail.
     */
    @Query("select w.balance from Wallet w where w.id = :walletId")
    Optional<BigDecimal> balanceOf(@Param("walletId") UUID walletId);
}