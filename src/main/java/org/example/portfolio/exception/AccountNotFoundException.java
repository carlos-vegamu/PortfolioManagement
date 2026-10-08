package org.example.portfolio.exception;

/** The external account service does not know the requested account. */
public class AccountNotFoundException extends PortfolioException {

    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
    }
}
