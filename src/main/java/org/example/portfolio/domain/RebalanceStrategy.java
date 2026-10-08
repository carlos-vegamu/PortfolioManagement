package org.example.portfolio.domain;

import java.util.Collection;

/**
 * Policy that decides which stocks to buy and sell. Injected into {@link Portfolio} so
 * alternative policies (thresholds, tax-aware, ...) can be added without touching it.
 */
public interface RebalanceStrategy {

    /**
     * @param holdings current positions
     * @param target   desired distribution
     * @param prices   source of current prices
     * @return the orders required to reach {@code target}; empty when already balanced
     */
    RebalancePlan plan(Collection<Stock> holdings, TargetAllocation target, MarketDataProvider prices);
}
