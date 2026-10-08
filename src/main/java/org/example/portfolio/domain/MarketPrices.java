package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.example.portfolio.exception.PriceUnavailableException;

/**
 * The prices one operation works with. Each ticker is looked up at most once, so every
 * calculation in the operation sees the same quote even if the source moves meanwhile, and
 * tickers the operation never needs are never fetched.
 *
 * <p>Not thread-safe: create one per operation and keep it on the thread running it.
 */
public final class MarketPrices {

    private final Function<String, BigDecimal> source;
    private final Map<String, BigDecimal> quoted = new HashMap<>();

    private MarketPrices(Function<String, BigDecimal> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Prices read lazily from {@code source}, which receives normalised tickers and throws
     * {@link PriceUnavailableException} for unknown ones.
     */
    public static MarketPrices from(Function<String, BigDecimal> source) {
        return new MarketPrices(source);
    }

    /** Fixed prices by normalised ticker; any other ticker raises {@link PriceUnavailableException}. */
    public static MarketPrices of(Map<String, BigDecimal> prices) {
        Map<String, BigDecimal> fixed = Map.copyOf(prices);
        return new MarketPrices(ticker -> {
            BigDecimal price = fixed.get(ticker);
            if (price == null) {
                throw new PriceUnavailableException(ticker);
            }
            return price;
        });
    }

    /**
     * @return the price per share, always positive
     * @throws PriceUnavailableException if the source has no price for {@code ticker}
     */
    public BigDecimal priceOf(String ticker) {
        return quoted.computeIfAbsent(ticker, this::fetch);
    }

    private BigDecimal fetch(String ticker) {
        BigDecimal price = source.apply(ticker);
        if (price == null) {
            throw new PriceUnavailableException(ticker);
        }
        if (price.signum() <= 0) {
            throw new IllegalStateException("Market data returned a non-positive price for " + ticker + ": " + price);
        }
        return price;
    }
}
