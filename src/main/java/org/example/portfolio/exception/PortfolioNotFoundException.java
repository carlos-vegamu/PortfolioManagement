package org.example.portfolio.exception;

/** The account has no portfolio yet. */
public class PortfolioNotFoundException extends PortfolioException {

    public PortfolioNotFoundException(String accountId) {
        super("Account " + accountId + " has no portfolio");
    }
}
