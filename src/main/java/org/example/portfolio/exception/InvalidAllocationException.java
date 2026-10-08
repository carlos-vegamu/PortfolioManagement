package org.example.portfolio.exception;

/** The target allocation is malformed, or a rebalance was requested without one. */
public class InvalidAllocationException extends PortfolioException {

    public InvalidAllocationException(String message) {
        super(message);
    }
}
