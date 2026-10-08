package org.example.portfolio.exception;

/** Base type for every business-rule violation raised by the portfolio module. */
public class PortfolioException extends RuntimeException {

    public PortfolioException(String message) {
        super(message);
    }

    public PortfolioException(String message, Throwable cause) {
        super(message, cause);
    }
}
