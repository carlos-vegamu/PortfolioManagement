package org.example.portfolio.service;

import java.math.BigDecimal;

import org.example.portfolio.exception.PriceUnavailableException;

/**
 * Contract with the external market-data service, implemented by another module in the main
 * application; this module ships only a mock. Implementations must be safe to call from
 * several threads. The service asks for each price at most once per operation.
 *
 * <p>It lives in {@code service}, its only caller, rather than {@code spi}: the domain never
 * calls it and only sees the prices of one operation, through {@code MarketPrices}.
 */
public interface MarketDataProvider {

    /**
     * @param ticker normalised ticker symbol
     * @return the latest price per share, always positive
     * @throws PriceUnavailableException if the ticker is unknown
     */
    BigDecimal getPrice(String ticker);
}
