package org.example.portfolio.domain;

import java.math.BigDecimal;

import org.example.portfolio.exception.PriceUnavailableException;

/**
 * Port to the external market-data service. Implemented by another module in the main
 * application; this module ships only a mock.
 */
public interface MarketDataProvider {

    /**
     * @param ticker normalised ticker symbol
     * @return the latest price per share, always positive
     * @throws PriceUnavailableException if the ticker is unknown
     */
    BigDecimal getPrice(String ticker);
}
