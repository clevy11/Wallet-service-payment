package com.example.walletservice.web;

import com.example.walletservice.service.TransferRejected;
import com.example.walletservice.service.WalletService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns domain failures into HTTP status codes.
 *
 * <p>Without this, every {@code RuntimeException} above becomes a 500. That is wrong
 * for most of what this service rejects: a caller asking for more money than they have
 * has sent a bad request, not hit a server fault, and a 500 tells them to retry -- so
 * a bug report follows, and any automated client retries a request that can never
 * succeed.
 *
 * <p>Each mapping below is chosen by what the caller should do next:
 *
 * <ul>
 *   <li><b>400</b> -- the request cannot be made valid. A client bug or a bad retry.</li>
 *   <li><b>404</b> -- the wallet is not the caller's, or does not exist. Deliberately
 *       identical for both: telling a caller that a wallet exists but is not theirs
 *       confirms it exists, which is a small leak of someone else's data and lets an
 *       attacker enumerate wallet ids.</li>
 *   <li><b>409</b> -- well-formed, but conflicts with current state. Insufficient
 *       funds: the caller may top up and try again with the same idempotency key.</li>
 *   <li><b>422</b> -- well-formed and consistent, but the operation is not defined for
 *       this state. Two different currencies, with no conversion in this system.</li>
 * </ul>
 */
@RestControllerAdvice
public class WalletExceptionHandler {

    @ExceptionHandler(TransferRejected.class)
    public ProblemDetail onTransferRejected(TransferRejected e) {
        HttpStatus status = switch (e.reason()) {
            case UNKNOWN_WALLET -> HttpStatus.NOT_FOUND;
            case INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
            case INSUFFICIENT_FUNDS -> HttpStatus.CONFLICT;
            case CURRENCY_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
            case WALLET_NOT_ACTIVE -> HttpStatus.CONFLICT;
        };

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(status.getReasonPhrase());
        // A machine-readable reason, so a client can branch without parsing prose.
        problem.setProperty("reason", e.reason().name());
        return problem;
    }

    @ExceptionHandler(WalletService.NoSuchWalletException.class)
    public ProblemDetail onUnknownWallet(WalletService.NoSuchWalletException e) {
        return notFound("no such wallet");
    }

    @ExceptionHandler(WalletService.NoSuchCustomerException.class)
    public ProblemDetail onUnknownCustomer(WalletService.NoSuchCustomerException e) {
        // Also 404, and for the same reason: the customer row arrives by a
        // UserCreated event, so it may not have landed yet. Saying so would help
        // nobody -- and "retry later" would be honest, but nothing here can be
        // retried any faster than the event arrives.
        return notFound("no customer for this account yet");
    }

    @ExceptionHandler(WalletService.InvalidCurrencyException.class)
    public ProblemDetail onBadCurrency(WalletService.InvalidCurrencyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid currency");
        return problem;
    }

    /**
     * Spring Boot's {@code ProblemDetail} for this status. Used only for its
     * content negotiation, which also sets the {@code application/problem+json}
     * content type a client expects.
     */
    private static ProblemDetail notFound(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setTitle(HttpStatus.NOT_FOUND.getReasonPhrase());
        return problem;
    }
}
