package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** One order suggested by a rebalance: buy or sell {@code quantity} shares at the reference {@code price}. */
public record TradeAction(String ticker, TradeSide side, long quantity, BigDecimal price) {

    public TradeAction {
        ticker = Stock.normalizeTicker(ticker);
        Objects.requireNonNull(side, "side");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + price);
        }
    }

    /** Estimated cash amount moved by this order. */
    public BigDecimal value() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
