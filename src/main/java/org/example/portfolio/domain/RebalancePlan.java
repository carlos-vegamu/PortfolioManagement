package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * The orders needed to bring a portfolio back to its target allocation. Sells are executed
 * before buys, so their proceeds can pay for the buys. The order lists are copied on
 * construction, so a plan cannot change between being reported and being applied.
 */
public record RebalancePlan(List<TradeAction> sells, List<TradeAction> buys) {

    private static final RebalancePlan EMPTY = new RebalancePlan(List.of(), List.of());

    public RebalancePlan {
        sells = List.copyOf(sells);
        buys = List.copyOf(buys);
        requireSide(sells, TradeSide.SELL);
        requireSide(buys, TradeSide.BUY);
    }

    public static RebalancePlan empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return sells.isEmpty() && buys.isEmpty();
    }

    /** Cash raised by the sells. */
    public BigDecimal proceeds() {
        return total(sells);
    }

    /** Cash spent by the buys. */
    public BigDecimal cost() {
        return total(buys);
    }

    private static BigDecimal total(List<TradeAction> actions) {
        BigDecimal total = BigDecimal.ZERO;
        for (TradeAction action : actions) {
            total = total.add(action.value());
        }
        return total;
    }

    private static void requireSide(List<TradeAction> actions, TradeSide side) {
        for (TradeAction action : actions) {
            if (action.side() != side) {
                throw new IllegalArgumentException("Expected only " + side + " orders but got " + action);
            }
        }
    }
}
