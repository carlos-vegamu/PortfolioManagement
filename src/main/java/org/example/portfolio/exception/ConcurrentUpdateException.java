package org.example.portfolio.exception;

/** Other updates to the same portfolio kept winning, so this one gave up; it can be retried. */
public class ConcurrentUpdateException extends PortfolioException {

    public ConcurrentUpdateException(String accountId, int attempts) {
        super("Account " + accountId + " is being updated by others; gave up after " + attempts
                + " attempts, try again");
    }
}
