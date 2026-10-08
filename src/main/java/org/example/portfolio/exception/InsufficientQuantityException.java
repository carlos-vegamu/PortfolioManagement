package org.example.portfolio.exception;

/** A sale was requested for more shares than the portfolio holds. */
public class InsufficientQuantityException extends PortfolioException {

    public InsufficientQuantityException(String ticker, long requested, long held) {
        super("Cannot sell " + requested + " " + ticker + ": only " + held + " held");
    }
}
