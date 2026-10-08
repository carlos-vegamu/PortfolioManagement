package org.example.portfolio.exception;

import java.math.BigDecimal;

/** A rebalance plan buys more than the portfolio's cash plus the plan's sale proceeds can pay for. */
public class InsufficientCashException extends PortfolioException {

    public InsufficientCashException(BigDecimal cost, BigDecimal available) {
        super("Cannot buy " + cost.toPlainString() + " worth of shares: only "
                + available.toPlainString() + " available");
    }
}
