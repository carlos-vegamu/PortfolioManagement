package org.example.portfolio.exception;

/**
 * Base type for the failures the portfolio module reports to its callers: business-rule
 * violations, plus {@link ConcurrentUpdateException}, which is contention and can be retried.
 */
public class PortfolioException extends RuntimeException {

    public PortfolioException(String message) {
        super(message);
    }

    public PortfolioException(String message, Throwable cause) {
        super(message, cause);
    }
}
