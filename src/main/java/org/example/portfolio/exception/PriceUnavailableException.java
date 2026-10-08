package org.example.portfolio.exception;

/** The market data provider has no price for the requested ticker. */
public class PriceUnavailableException extends PortfolioException {

    public PriceUnavailableException(String ticker) {
        super("No market price available for " + ticker);
    }
}
