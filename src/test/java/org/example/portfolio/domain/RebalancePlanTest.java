package org.example.portfolio.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RebalancePlanTest {

    private static final TradeAction BUY = new TradeAction("AAPL", TradeSide.BUY, 3, new BigDecimal("190"));
    private static final TradeAction SELL = new TradeAction("meta", TradeSide.SELL, 2, new BigDecimal("500"));

    @Test
    void keepsSellsAndBuysApart() {
        RebalancePlan plan = new RebalancePlan(List.of(SELL), List.of(BUY));

        assertEquals(List.of(BUY), plan.buys());
        assertEquals(List.of(SELL), plan.sells());
        assertFalse(plan.isEmpty());
    }

    @Test
    void rejectsOrdersOnTheWrongSide() {
        assertThrows(IllegalArgumentException.class, () -> new RebalancePlan(List.of(BUY), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RebalancePlan(List.of(), List.of(SELL)));
    }

    @Test
    void proceedsAndCostAddUpTheOrders() {
        RebalancePlan plan = new RebalancePlan(List.of(SELL), List.of(BUY, BUY));

        assertEquals(0, new BigDecimal("1000").compareTo(plan.proceeds()));
        assertEquals(0, new BigDecimal("1140").compareTo(plan.cost()));
    }

    @Test
    void emptyPlanHasNoOrders() {
        assertTrue(RebalancePlan.empty().isEmpty());
        assertTrue(RebalancePlan.empty().buys().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(RebalancePlan.empty().proceeds()));
    }

    @Test
    void planIsDefensivelyCopied() {
        List<TradeAction> source = new ArrayList<>(List.of(BUY));
        RebalancePlan plan = new RebalancePlan(List.of(), source);
        source.add(BUY);

        assertEquals(1, plan.buys().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.buys().add(BUY));
    }

    @Test
    void tradeActionValueIsPriceTimesQuantityAndTickerIsNormalised() {
        assertEquals(0, new BigDecimal("1000").compareTo(SELL.value()));
        assertEquals("META", SELL.ticker());
    }

    @Test
    void tradeActionRejectsInvalidArguments() {
        assertThrows(NullPointerException.class, () -> new TradeAction("META", null, 1, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new TradeAction("META", TradeSide.BUY, 0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new TradeAction("META", TradeSide.BUY, 1, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TradeAction("META", TradeSide.BUY, 1, null));
    }
}
