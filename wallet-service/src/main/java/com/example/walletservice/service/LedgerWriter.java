package com.example.walletservice.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.example.walletservice.domain.Customer;
import com.example.walletservice.domain.OutboxEvent;
import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletEntry;
import com.example.walletservice.repository.CustomerRepository;
import com.example.walletservice.repository.OutboxEventRepository;
import com.example.walletservice.repository.WalletEntryRepository;
import com.example.walletservice.repository.WalletRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes both ledger legs, both balance updates and the outbox row, in one
 * transaction, on its own bean.
 *
 * <p><b>Why a separate bean from {@link TransferService}.</b> Spring's transaction
 * proxy is bypassed when a bean calls its own method, so a {@code @Transactional}
 * method invoked from another method of the same class runs with <em>no
 * transaction</em> -- silently, and invisibly, because the repositories' own
 * {@code save()} opens an implicit transaction that hides the problem. This class is
 * the transaction boundary; {@code TransferService} orchestrates across it.
 *
 * <p><b>Ordering.</b> Both balance updates run before either ledger entry is
 * inserted. The unique index on {@code reference} is what makes a retry safe, so a
 * duplicate fails <em>after</em> its debits have already run. That is only sound
 * because all of it is one transaction: the constraint violation marks the
 * transaction rollback-only and the debit is undone with it. Inserting first would
 * discover the duplicate before moving money, which saves work but puts the
 * uniqueness check upstream of the thing it protects, where it no longer proves
 * anything about the balances.
 */
@Service
public class LedgerWriter {

    static final String EVENT_TYPE = "wallet.transfer.completed";

    private final CustomerRepository customers;
    private final WalletRepository wallets;
    private final WalletEntryRepository entries;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public LedgerWriter(CustomerRepository customers, WalletRepository wallets,
                        WalletEntryRepository entries, OutboxEventRepository outbox,
                        ObjectMapper objectMapper) {
        this.customers = customers;
        this.wallets = wallets;
        this.entries = entries;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    /**
     * Moves the money and announces it, atomically.
     *
     * @param ownerId the authenticated caller, taken from the token's {@code sub}.
     *                Resolved here, inside the transaction, because ownership is a
     *                precondition of moving the money and not a detail of the HTTP
     *                layer: a caller must not be able to reach a wallet id that is
     *                not theirs by going through any path other than the controller.
     *                It gates the <em>source</em> only -- see {@code requirePayee}
     * @param correlationId captured from the inbound request, never minted here
     * @return the transfer id and the source balance after the debit, so the caller
     *         can answer without re-reading a row it has already moved
     * @throws TransferRejected if the money cannot move, for a stated reason
     */
    @Transactional
    public Written write(UUID ownerId, Transfer transfer, UUID correlationId) {
        UUID customerId = customerIdOf(ownerId);

        Wallet source = requireOwnedActive(customerId, transfer.sourceWalletId());
        Wallet destination = requirePayee(transfer.destinationWalletId());

        if (!source.getCurrency().equals(destination.getCurrency())) {
            // No FX in this system. Silently converting would need a rate, and a rate
            // is a number that can be wrong, so refusing is the only honest option.
            throw new TransferRejected(TransferRejected.Reason.CURRENCY_MISMATCH,
                    "cannot transfer %s to %s without conversion"
                            .formatted(source.getCurrency(), destination.getCurrency()));
        }

        if (wallets.debitIfSufficient(source.getId(), transfer.amount()) == 0) {
            throw TransferRejected.insufficientFunds(source.getId(), transfer.amount());
        }

        if (wallets.credit(destination.getId(), transfer.amount()) == 0) {
            throw new TransferRejected(TransferRejected.Reason.WALLET_NOT_ACTIVE,
                    "destination wallet stopped accepting money");
        }

        UUID transferId = transfer.transferId() == null ? UUID.randomUUID() : transfer.transferId();

        // balance_after is read back per wallet rather than computed, because the
        // conditional UPDATE bypassed the persistence context: the managed entities
        // above still hold the pre-update figures, and writing those into the ledger
        // would corrupt the audit trail while looking correct.
        BigDecimal sourceBalanceAfter = balanceAfter(source.getId());

        entries.save(new WalletEntry(UUID.randomUUID(), source.getId(),
                TransferLeg.OUT.entryType(), transfer.amount(), sourceBalanceAfter,
                TransferLeg.OUT.referenceFor(transfer.idempotencyKey()), transferId));

        entries.save(new WalletEntry(UUID.randomUUID(), destination.getId(),
                TransferLeg.IN.entryType(), transfer.amount(),
                balanceAfter(destination.getId()),
                TransferLeg.IN.referenceFor(transfer.idempotencyKey()), transferId));

        outbox.save(new OutboxEvent(transferId, "Transfer", transferId, EVENT_TYPE, correlationId,
                payloadOf(transferId, transfer)));

        return new Written(transferId, sourceBalanceAfter);
    }

    /**
     * What the writer could see and the orchestrator could not, having just performed
     * updates that bypassed the persistence context.
     */
    public record Written(UUID transferId, BigDecimal sourceBalanceAfter) {
    }

    private UUID customerIdOf(UUID ownerId) {
        return customers.findByOwnerId(ownerId)
                .map(Customer::getId)
                .orElseThrow(() -> new TransferRejected(TransferRejected.Reason.UNKNOWN_WALLET,
                        "no customer for this account"));
    }

    /**
     * Loads a wallet, insists this caller owns it, and insists it can move money.
     *
     * <p>Ownership is checked inside the same transaction as the debit, not in the
     * controller. A check in the web layer is one call path's worth of protection: the
     * service is reachable from a scheduled job, a message listener and tomorrow's
     * batch import, and each of those would have to remember to repeat it. Here it is
     * written once and cannot be bypassed.
     *
     * <p>The status check is duplicated in the SQL on purpose. {@code status = 'ACTIVE'}
     * in the WHERE clause is what makes the UPDATE safe under concurrency; this check is
     * what turns "0 rows affected" into an accurate reason rather than a guess.
     *
     * <p>A wallet belonging to somebody else reports as "no such wallet" rather than
     * "not yours". Confirming that an id exists is itself a leak: it tells the caller
     * the id is real, and lets them probe for live accounts.
     */
    private Wallet requireOwnedActive(UUID customerId, UUID walletId) {
        Wallet wallet = wallets.findById(walletId)
                .filter(candidate -> candidate.getCustomerId().equals(customerId))
                .orElseThrow(() -> new TransferRejected(TransferRejected.Reason.UNKNOWN_WALLET,
                        "no wallet " + walletId));
        if (!wallet.getStatus().isTransactable()) {
            throw new TransferRejected(TransferRejected.Reason.WALLET_NOT_ACTIVE,
                    "wallet %s is %s".formatted(walletId, wallet.getStatus()));
        }
        return wallet;
    }

    /**
     * Loads the destination wallet, which belongs to the payee rather than to the
     * caller.
     *
     * <p>A transfer is a payment, so the two legs are on opposite sides of the
     * ownership boundary: the caller's own money leaves a wallet they own, and
     * somebody else's wallet is credited. That is also why the source is checked
     * strictly and the destination only for existence and status -- the destination
     * check is deliberately <em>not</em> an ownership check, because "you may only pay
     * wallets you know about" is a rule this schema has nowhere to store.
     *
     * <p><b>The abuse surface this leaves, stated plainly.</b> Any authenticated
     * caller can credit any wallet id they can guess: that is a spamming and
     * enumeration vector, not a theft vector -- it cannot move money out, only in,
     * and the payee's ledger is unchanged by the sender's existence. Real systems
     * close it with a payee the caller has previously interacted with, or an
     * approval step. Neither exists here; if that matters, it belongs in a payee
     * table rather than as a conditional in this method.
     */
    private Wallet requirePayee(UUID walletId) {
        Wallet wallet = wallets.findById(walletId)
                .orElseThrow(() -> new TransferRejected(TransferRejected.Reason.UNKNOWN_WALLET,
                        "no wallet " + walletId));
        if (!wallet.getStatus().isTransactable()) {
            throw new TransferRejected(TransferRejected.Reason.WALLET_NOT_ACTIVE,
                    "wallet %s is %s".formatted(walletId, wallet.getStatus()));
        }
        return wallet;
    }

    private BigDecimal balanceAfter(UUID walletId) {
        return wallets.balanceOf(walletId)
                .orElseThrow(() -> new IllegalStateException("wallet vanished mid-transfer: " + walletId));
    }

    private String payloadOf(UUID transferId, Transfer transfer) {
        return objectMapper.writeValueAsString(new TransferCompleted(
                transferId,
                transfer.sourceWalletId(),
                transfer.destinationWalletId(),
                transfer.amount(),
                transfer.idempotencyKey()));
    }

    /** The contract other services consume. Public fields via a record accessor. */
    public record TransferCompleted(
            UUID transferId,
            UUID sourceWalletId,
            UUID destinationWalletId,
            BigDecimal amount,
            String idempotencyKey) {
    }
}