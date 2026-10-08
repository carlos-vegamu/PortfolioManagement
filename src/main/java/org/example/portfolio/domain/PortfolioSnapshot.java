package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only copy of a portfolio's state. This is what the service layer hands to
 * other modules so they can never mutate the aggregate behind its back.
 *
 * @param targetAllocation target percentages by ticker; empty when none has been defined
 */
public record PortfolioSnapshot(String accountId, Set<Stock> stocks, Map<String, BigDecimal> targetAllocation) {

    public PortfolioSnapshot {
        // copies keep the caller's iteration order (portfolio data is sorted by ticker)
        stocks = Collections.unmodifiableSet(new LinkedHashSet<>(stocks));
        targetAllocation = Collections.unmodifiableMap(new LinkedHashMap<>(targetAllocation));
    }
}
