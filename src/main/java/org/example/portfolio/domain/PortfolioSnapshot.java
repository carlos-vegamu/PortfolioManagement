package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Read-only copy of a portfolio's state. This is what the service layer hands to
 * other modules so they can never mutate the aggregate behind its back.
 *
 * @param stocks           positions by ticker, sorted
 * @param cash             uninvested cash, e.g. rebalance proceeds that did not buy a whole share
 * @param targetAllocation the target allocation, if one has been defined
 */
public record PortfolioSnapshot(String accountId, SortedMap<String, Stock> stocks, BigDecimal cash,
                                Optional<TargetAllocation> targetAllocation) {

    public PortfolioSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        stocks = Collections.unmodifiableSortedMap(new TreeMap<>(stocks));
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(targetAllocation, "targetAllocation");
    }
}
