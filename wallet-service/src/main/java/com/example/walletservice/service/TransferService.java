package com.example.walletservice.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.walletservice.domain.WalletEntry;
import com.example.walletservice.repository.WalletEntryRepository;
import com.example.walletservice.repository.WalletRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a transfer request is new or a retry, and runs it.
 *
 * <p><b>Not transactional, on purpose.</b> This class must be able to catch a failed
 * transaction and then read the row the winner wrote. Catching inside a transaction
 * does not undo it -- the transaction is already marked rollback-only by the
 * constraint violation, and the only way to clear that is for the transaction to end.
 * So the write happens in {@link LedgerWriter}, which owns the boundary, and this
 * class runs outside it.
 *
 * <p>A self-invoked {@code @Transactional} would have made this quietly wrong: Spring's
 * proxy is bypassed when a bean calls its own method, so the inner call would run
 * with no transaction, the duplicate would never violate anything, and the caller
 * would be charged twice.
 *
 * <p><b>The race this handles.</b> Two identical requests arrive at the same instant,
 * both miss the fast path below, both run their debits, and both try to insert the
 * outgoing entry. One wins; the other violates the unique index and its whole
 * transaction rolls back. That request is then answered from the winner's row. The
 * duplicate did briefly debit and credit, and the rollback is what undid it -- so the
 * safety of this depends on the ledger writes being inside the transaction, not merely
 * near it.
 */
@Service
public class TransferService {

    private final WalletRepository wallets;
    private final WalletEntryRepository entries;
    private final LedgerWriter writer;

    public TransferService(WalletRepository wallets, WalletEntryRepository entries,
                           LedgerWriter writer) {
        this.wallets = wallets;
        this.entries = entries;
        this.writer = writer;
    }

    /**
     * Executes a transfer, or returns the original result of an earlier identical one.
     *
     * @param ownerId      the authenticated caller, from the token's {@code sub}
     * @param correlationId from {@code X-Correlation-Id}, written into the outbox row
     */
    public TransferResult transfer(UUID ownerId, Transfer transfer, UUID correlationId) {
        // Fast path: an already-recorded attempt. Costs one indexed lookup and avoids
        // the write attempt entirely, which matters because retries are the expected
        // case for a client that timed out -- not an exotic one.
        var existing = entries.findByReference(TransferLeg.OUT.referenceFor(transfer.idempotencyKey()));
        if (existing.isPresent()) {
            return TransferResult.replayed(existing.get(), transfer);
        }

        try {
            LedgerWriter.Written written = writer.write(ownerId, transfer, correlationId);
            return TransferResult.completed(written, transfer, false);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent identical request. Its transaction has
            // rolled back by now, so its rows are visible and consistent, and the
            // duplicate is answered from them.
            //
            // Rethrowing anything else would be wrong: a constraint violation here can
            // only mean the reference already exists, since the other constraints are
            // satisfied by the state this code just validated. Narrowing the check
            // further would need the driver's constraint name, which is vendor detail.
            return entries.findByReference(TransferLeg.OUT.referenceFor(transfer.idempotencyKey()))
                    .map(entry -> TransferResult.replayed(entry, transfer))
                    .orElseThrow(() -> e);
        }
    }

    /** What the caller is told, whether or not this request is the one that moved the money. */
    public record TransferResult(UUID transferId, BigDecimal amount, UUID sourceWalletId,
                                 UUID destinationWalletId, BigDecimal sourceBalance,
                                 boolean replayed) {

        static TransferResult completed(LedgerWriter.Written written, Transfer transfer, boolean replayed) {
            return new TransferResult(written.transferId(), transfer.amount(), transfer.sourceWalletId(),
                    transfer.destinationWalletId(), written.sourceBalanceAfter(), replayed);
        }

        /**
         * Rebuilt from the ledger entry, not from the request.
         *
         * <p>The transfer id comes from {@code transfer_id} on the entry rather than
         * from the retrying request, which has none -- a retry only carries the
         * idempotency key. The balance reported is the one recorded when the money
         * actually moved, so the replay describes history rather than what this
         * request would have caused.
         */
        static TransferResult replayed(WalletEntry entry, Transfer transfer) {
            return new TransferResult(entry.getTransferId(), entry.getAmount(),
                    entry.getWalletId(), transfer.destinationWalletId(),
                    entry.getBalanceAfter(), true);
        }
    }
}