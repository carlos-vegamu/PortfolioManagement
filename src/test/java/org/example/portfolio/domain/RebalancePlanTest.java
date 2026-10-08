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
    void splitsActionsIntoBuysAndSells() {
        RebalancePlan plan = new RebalancePlan(List.of(BUY, SELL));

        assertEquals(List.of(BUY), plan.buys());
        assertEquals(List.of(SELL), plan.sells());
        assertFalse(plan.isEmpty());
    }

    @Test
    void emptyPlanHasNoActions() {
        assertTrue(RebalancePlan.empty().isEmpty());
        assertTrue(RebalancePlan.empty().buys().isEmpty());
    }

    @Test
    void planIsDefensivelyCopied() {
        List<TradeAction> source = new ArrayList<>(List.of(BUY));
        RebalancePlan plan = new RebalancePlan(source);
        source.add(SELL);

        assertEquals(1, plan.actions().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.actions().add(SELL));
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
