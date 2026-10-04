package com.example.walletservice.web;

import java.util.UUID;

import com.example.walletservice.domain.Customer;
import com.example.walletservice.repository.CustomerRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The customer's own wallet, resolved from the token rather than from anything the
 * caller supplied.
 *
 * <p>The only thing this endpoint trusts is {@code sub}. There is no path variable and
 * no id in the body, because a path variable would let any authenticated caller read
 * anyone's wallet by guessing a UUID, and the ownership check would be a parameter the
 * caller controls. Deriving the customer from the token makes "whose data is this"
 * unanswerable by the caller.
 */
@RestController
@RequestMapping("/wallets/me")
public class MyWalletController {

    private final CustomerRepository customers;

    public MyWalletController(CustomerRepository customers) {
        this.customers = customers;
    }

    @GetMapping
    public ResponseEntity<MyWallet> mine(@AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());

        return customers.findByOwnerId(ownerId)
                .map(customer -> ResponseEntity.ok(MyWallet.of(customer, jwt)))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "no wallet for this account yet"));
    }

    /**
     * 404 rather than an empty body, and the message says "not yet". The customer row
     * is created by consuming {@code UserCreated} off {@code auth.events}, so a
     * user who logged in successfully can legitimately reach this before their row
     * exists. A plain 404 would be indistinguishable from a genuinely unknown account
     * and would send a caller looking for a bug that is really propagation lag.
     */
    public record MyWallet(
            UUID id,
            UUID ownerId,
            String displayName,
            String defaultCurrency,
            String roles,
            String issuer) {

        static MyWallet of(Customer customer, Jwt jwt) {
            return new MyWallet(
                    customer.getId(),
                    customer.getOwnerId(),
                    customer.getDisplayName(),
                    customer.getDefaultCurrency(),
                    // roles is echoed back from the token rather than read from a
                    // local table: authorization is decided by the issuer, and
                    // wallet-service has no copy of the user's roles to keep in step.
                    // Anything derived from them must come from the same claim.
                    jwt.getClaimAsString("roles"),
                    jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
        }
    }
}