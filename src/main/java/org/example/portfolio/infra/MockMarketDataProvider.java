package org.example.portfolio.infra;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.example.portfolio.exception.PriceUnavailableException;
import org.example.portfolio.service.MarketDataProvider;

/** Stand-in for the external market-data service, backed by a thread-safe price table. */
public class MockMarketDataProvider implements MarketDataProvider {

    private final Map<String, BigDecimal> prices = new ConcurrentHashMap<>();

    /** A provider preloaded with a handful of well-known tickers. */
    public static MockMarketDataProvider withDefaultPrices() {
        MockMarketDataProvider provider = new MockMarketDataProvider();
        provider.setPrice("AAPL", new BigDecimal("190.00"));
        provider.setPrice("AMZN", new BigDecimal("180.00"));
        provider.setPrice("GOOGL", new BigDecimal("170.00"));
        provider.setPrice("META", new BigDecimal("500.00"));
        provider.setPrice("MSFT", new BigDecimal("420.00"));
        provider.setPrice("NVDA", new BigDecimal("900.00"));
        provider.setPrice("TSLA", new BigDecimal("175.00"));
        return provider;
    }

    /**
     * @param ticker normalised ticker symbol (upper case, e.g. {@code "IBM"}), as the service asks
     *               for it; {@code "ibm"} would be stored as a different, never-requested ticker
     */
    public void setPrice(String ticker, BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + price);
        }
        prices.put(ticker, price);
    }

    @Override
    public BigDecimal getPrice(String ticker) {
        BigDecimal price = prices.get(ticker);
        if (price == null) {
            throw new PriceUnavailableException(ticker);
        }
        return price;
    }
}
