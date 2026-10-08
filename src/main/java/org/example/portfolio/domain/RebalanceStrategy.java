package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.SortedMap;

/**
 * Policy that decides which stocks to buy and sell. Passed to {@link Portfolio#rebalance} so
 * alternative policies (thresholds, tax-aware, ...) can be added without touching it.
 */
public interface RebalanceStrategy {

    /**
     * @param holdings current positions by ticker
     * @param cash     uninvested cash the plan may spend
     * @param target   desired distribution
     * @param prices   prices for this operation
     * @return the orders required to reach {@code target}, whose buys cost no more than {@code cash}
     *         plus the proceeds of its sells; empty when already balanced
     */
    RebalancePlan plan(SortedMap<String, Stock> holdings, BigDecimal cash, TargetAllocation target, MarketPrices prices);
}
