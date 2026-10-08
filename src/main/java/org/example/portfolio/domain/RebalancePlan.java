package org.example.portfolio.domain;

import java.util.List;

/** The orders needed to bring a portfolio back to its target allocation. */
public record RebalancePlan(List<TradeAction> actions) {

    public RebalancePlan {
        actions = List.copyOf(actions);
    }

    public static RebalancePlan empty() {
        return new RebalancePlan(List.of());
    }

    public List<TradeAction> buys() {
        return filter(TradeSide.BUY);
    }

    public List<TradeAction> sells() {
        return filter(TradeSide.SELL);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }

    private List<TradeAction> filter(TradeSide side) {
        return actions.stream().filter(a -> a.side() == side).toList();
    }
}
