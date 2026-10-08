package org.example.portfolio.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.portfolio.domain.MarketDataProvider;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.Stock;
import org.example.portfolio.domain.TargetAllocation;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.domain.TradeSide;
import org.example.portfolio.exception.PriceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProportionalRebalanceStrategyTest {

    @Mock
    private MarketDataProvider prices;

    private final ProportionalRebalanceStrategy strategy = new ProportionalRebalanceStrategy();

    @BeforeEach
    void setUp() {
        lenient().when(prices.getPrice("META")).thenReturn(bd("500"));
        lenient().when(prices.getPrice("AAPL")).thenReturn(bd("190"));
        lenient().when(prices.getPrice("TSLA")).thenReturn(bd("175"));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Stock stock(String ticker, long qty) {
        return new Stock(ticker, qty, BigDecimal.ONE);
    }

    private static TargetAllocation target(Object... pairs) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], bd((String) pairs[i + 1]));
        }
        return TargetAllocation.of(map);
    }

    @Test
    void sellsOverweightAndBuysUnderweight() {
        // META 5,000 + AAPL 9,500 = 14,500; target 40/60 => META 5,800 (+1.6 sh -> buy 1) and AAPL 8,700 (-4.2 sh -> sell 4)
        RebalancePlan plan = strategy.plan(
                List.of(stock("META", 10), stock("AAPL", 50)), target("META", "40", "AAPL", "60"), prices);

        assertEquals(List.of(new TradeAction("AAPL", TradeSide.SELL, 4, bd("190"))), plan.sells());
        assertEquals(List.of(new TradeAction("META", TradeSide.BUY, 1, bd("500"))), plan.buys());
    }

    @Test
    void sellsHoldingsThatAreNotInTheTargetAndBuysNewOnes() {
        // TSLA 1,750 is all there is; 100% AAPL => sell all 10 TSLA, buy floor(1750/190)=9 AAPL
        RebalancePlan plan = strategy.plan(List.of(stock("TSLA", 10)), target("AAPL", "100"), prices);

        assertEquals(List.of(new TradeAction("TSLA", TradeSide.SELL, 10, bd("175"))), plan.sells());
        assertEquals(List.of(new TradeAction("AAPL", TradeSide.BUY, 9, bd("190"))), plan.buys());
    }

    @Test
    void alreadyBalancedPortfolioNeedsNoTrades() {
        // META 10 x 500 = 5,000 and AAPL 20 x 250 = 5,000 -> exactly 50/50
        when(prices.getPrice("AAPL")).thenReturn(bd("250"));
        RebalancePlan plan = strategy.plan(
                List.of(stock("META", 10), stock("AAPL", 20)), target("META", "50", "AAPL", "50"), prices);

        assertTrue(plan.isEmpty());
    }

    @Test
    void differencesSmallerThanOneShareProduceNoOrder() {
        // 10 META (5,000) + 1 AAPL (190): target 96/4 => AAPL target 207.6 -> +0.09 share
        RebalancePlan plan = strategy.plan(
                List.of(stock("META", 10), stock("AAPL", 1)), target("META", "96", "AAPL", "4"), prices);

        assertTrue(plan.isEmpty());
    }

    @Test
    void emptyPortfolioYieldsEmptyPlanWithoutPriceLookups() {
        RebalancePlan plan = strategy.plan(List.of(), target("META", "100"), prices);

        assertTrue(plan.isEmpty());
        verifyNoInteractions(prices);
    }

    @Test
    void sellsAreRoundedToTheNearestShare() {
        // META 7 x 500 + AAPL 13 x 190 = 5,970; 30% META target = 1,791 -> sell 1,709 / 500 = 3.4 -> 3 shares
        // 30% AAPL target = 1,791 -> sell 679 / 190 = 3.57 -> 4 shares
        RebalancePlan plan = strategy.plan(
                List.of(stock("META", 7), stock("AAPL", 13)), target("META", "30", "AAPL", "30", "TSLA", "40"), prices);

        assertEquals(3, plan.sells().stream().filter(a -> a.ticker().equals("META")).findFirst().orElseThrow().quantity());
        assertEquals(4, plan.sells().stream().filter(a -> a.ticker().equals("AAPL")).findFirst().orElseThrow().quantity());
    }

    @Test
    void buysAreCappedByTheProceedsOfTheSells() {
        // sells bring 3*500 + 4*190 = 2,260, enough for only 12 TSLA (2,100) although 13 would be wanted (2,388 target)
        RebalancePlan plan = strategy.plan(
                List.of(stock("META", 7), stock("AAPL", 13)), target("META", "30", "AAPL", "30", "TSLA", "40"), prices);

        assertEquals(List.of(new TradeAction("TSLA", TradeSide.BUY, 12, bd("175"))), plan.buys());
    }

    @Test
    void buysAreOrderedByLargestGapFirst() {
        when(prices.getPrice("AAPL")).thenReturn(bd("100"));
        // total 6,000 (META 5,000 + AAPL 1,000); META is not in the target so it is sold completely.
        // TSLA 50% = 3,000 -> gap 3,000 (17 shares); AAPL 50% = 3,000 -> gap 2,000 (20 shares)
        RebalancePlan plan = strategy.plan(
                List.of(stock("META", 10), stock("AAPL", 10)), target("TSLA", "50", "AAPL", "50"), prices);

        TradeAction tsla = plan.buys().stream().filter(a -> a.ticker().equals("TSLA")).findFirst().orElseThrow();
        TradeAction aapl = plan.buys().stream().filter(a -> a.ticker().equals("AAPL")).findFirst().orElseThrow();
        assertEquals(17, tsla.quantity());
        assertEquals(20, aapl.quantity());
        assertEquals(List.of(tsla, aapl), plan.buys(), "larger gap first");
    }

    @Test
    void neverBuysMoreThanItSells() {
        RebalancePlan plan = strategy.plan(
                List.of(stock("META", 7), stock("AAPL", 13)), target("META", "30", "AAPL", "30", "TSLA", "40"), prices);

        BigDecimal sold = plan.sells().stream().map(TradeAction::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal bought = plan.buys().stream().map(TradeAction::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(bought.compareTo(sold) <= 0, "bought " + bought + " > sold " + sold);
    }

    @Test
    void missingPriceIsPropagated() {
        when(prices.getPrice("TSLA")).thenThrow(new PriceUnavailableException("TSLA"));

        assertThrows(PriceUnavailableException.class,
                () -> strategy.plan(List.of(stock("META", 1)), target("META", "50", "TSLA", "50"), prices));
    }
}
