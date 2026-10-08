package org.example.portfolio.exception;

/** An account can own at most one portfolio. */
public class PortfolioAlreadyExistsException extends PortfolioException {

    public PortfolioAlreadyExistsException(String accountId) {
        super("Account " + accountId + " already has a portfolio");
    }
}
