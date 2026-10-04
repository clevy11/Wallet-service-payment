package com.example.walletservice.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.example.walletservice.domain.Wallet;
import com.example.walletservice.domain.WalletEntry;
import com.example.walletservice.service.Transfer;
import com.example.walletservice.service.TransferService;
import com.example.walletservice.service.TransferService.TransferResult;
import com.example.walletservice.service.WalletService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wallets and transfers for whoever the token belongs to.
 *
 * <p>Nothing here accepts an owner id. Every endpoint derives the caller from
 * {@code sub}. The only ids a caller may supply are wallet ids, and for a transfer the
 * source wallet is checked against the wallets that caller owns while the destination
 * is somebody else's. An endpoint taking an owner id would move the authorisation
 * decision into a parameter the caller controls.
 *
 * <p>{@code GET /wallets/me} is the pre-existing profile endpoint and stays as it is;
 * it returns the customer row rather than a wallet. This controller owns
 * {@code /wallets}, the wallets themselves.
 */
@RestController
@RequestMapping
public class WalletController {

    /** Named rather than inlined, so {@link TransferRejected} stays the single vocabulary. */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final WalletService wallets;
    private final TransferService transfers;

    public WalletController(WalletService wallets, TransferService transfers) {
        this.wallets = wallets;
        this.transfers = transfers;
    }

    @GetMapping("/wallets")
    public List<WalletView> mine(@AuthenticationPrincipal Jwt jwt) {
        return wallets.mine(ownerOf(jwt)).stream().map(WalletView::of).toList();
    }

    /**
     * Opens a wallet. Idempotent per currency, so a retry returns the wallet that
     * exists rather than a second one.
     *
     * <p>201 when this call created it, 200 when it was already there. The distinction
     * is the whole value of the {@code created} flag: a 201 tells the client it now
     * owns something new, while a 200 tells it its retry worked. Answering 201 either
     * way would make a double-click look like two accounts were opened.
     */
    @PostMapping("/wallets")
    public ResponseEntity<WalletView> create(@AuthenticationPrincipal Jwt jwt,
                                             @RequestBody CreateWalletRequest request) {
        WalletService.Created result = wallets.create(ownerOf(jwt), request.currency());
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(WalletView.of(result.wallet()));
    }

    @GetMapping("/wallets/{walletId}/entries")
    public HistoryView history(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable UUID walletId,
                               @RequestParam(defaultValue = "50") int limit) {
        WalletService.History history = wallets.history(ownerOf(jwt), walletId, limit);
        return new HistoryView(history.effectiveLimit(),
                history.entries().stream().map(EntryView::of).toList());
    }

    /**
     * Moves money between two of the caller's wallets.
     *
     * <p>Requires {@code Idempotency-Key}. This is the endpoint where the header is
     * not optional convenience: "debit 50" and "debit 50 again" are indistinguishable
     * by business key, so a client that times out and retries has no other way to
     * avoid paying twice. Making the key mandatory is the only way to distinguish a
     * retry from a genuine second transfer.
     */
    @PostMapping("/transfers")
    public ResponseEntity<TransferView> transfer(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = CorrelationId.HEADER, required = false) String correlationHeader,
            @RequestBody TransferRequest request) {

        TransferResult result = transfers.transfer(
                ownerOf(jwt),
                new Transfer(
                        request.sourceWalletId(),
                        request.destinationWalletId(),
                        request.amount(),
                        // Missing key reaches Transfer's constructor, which rejects it as
                        // INVALID_AMOUNT rather than letting an unkeyed transfer through.
                        idempotencyKey,
                        null),
                CorrelationId.resolve(correlationHeader));

        return ResponseEntity
                .status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(TransferView.of(result));
    }

    private static UUID ownerOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public record CreateWalletRequest(String currency) {
    }

    /**
     * The caller names the wallet to pay from and the wallet to pay into.
     *
     * <p>Both wallet ids are validated in the service layer, which is where ownership
     * belongs -- a check here would have to be repeated by every future caller of the
     * service. The source must belong to the caller; the destination is the payee's
     * and is not the caller's to choose beyond its id.
     */
    public record TransferRequest(UUID sourceWalletId, UUID destinationWalletId, BigDecimal amount) {
    }

    public record WalletView(UUID id, UUID customerId, String currency, String balance, String status) {

        static WalletView of(Wallet wallet) {
            return new WalletView(wallet.getId(), wallet.getCustomerId(), wallet.getCurrency(),
                    wallet.getBalance().toPlainString(), wallet.getStatus().name());
        }
    }

    /**
     * Balances are strings on the wire.
     *
     * <p>Not an oversight and not laziness. JSON numbers are IEEE 754 doubles, and
     * {@code 0.1} has no exact binary representation -- a client parsing this as a
     * double would end up with a balance that differs from the database's by a
     * fraction of a cent. Emitting a decimal string forces the client to parse it as
     * a decimal, which is the only way it survives the round trip.
     */
    public record EntryView(UUID id, String type, String amount, String balanceAfter,
                            String reference, UUID transferId, String createdAt) {

        static EntryView of(WalletEntry entry) {
            return new EntryView(entry.getId(), entry.getEntryType().name(),
                    entry.getAmount().toPlainString(), entry.getBalanceAfter().toPlainString(),
                    entry.getReference(), entry.getTransferId(), entry.getCreatedAt().toString());
        }
    }

    public record HistoryView(int limit, List<EntryView> entries) {
    }

    public record TransferView(UUID transferId, UUID sourceWalletId, UUID destinationWalletId,
                               String amount, String sourceBalance, boolean replayed) {

        static TransferView of(TransferResult result) {
            return new TransferView(result.transferId(), result.sourceWalletId(),
                    result.destinationWalletId(),
                    result.amount() == null ? null : result.amount().toPlainString(),
                    result.sourceBalance() == null ? null : result.sourceBalance().toPlainString(),
                    result.replayed());
        }
    }
}
