package org.example.portfolio.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;
import org.example.portfolio.exception.PriceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioTest {

    @Mock
    private RebalanceStrategy strategy;
    @Mock
    private MarketDataProvider prices;

    private Portfolio portfolio;

    @BeforeEach
    void setUp() {
        portfolio = new Portfolio("acc-1", strategy);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static TargetAllocation target(String t1, String p1, String t2, String p2) {
        return TargetAllocation.of(Map.of(t1, bd(p1), t2, bd(p2)));
    }

    // ---- construction ------------------------------------------------------

    @Test
    void constructorTrimsAccountId() {
        assertEquals("acc-9", new Portfolio("  acc-9 ", strategy).getAccountId());
    }

    @Test
    void constructorRejectsBlankAccountId() {
        assertThrows(IllegalArgumentException.class, () -> new Portfolio(" ", strategy));
        assertThrows(IllegalArgumentException.class, () -> new Portfolio(null, strategy));
    }

    @Test
    void constructorRejectsNullStrategy() {
        assertThrows(NullPointerException.class, () -> new Portfolio("acc", null));
    }

    @Test
    void newPortfolioIsEmptyWithoutTarget() {
        assertTrue(portfolio.getStocks().isEmpty());
        assertTrue(portfolio.getTargetAllocation().isEmpty());
    }

    // ---- buying ------------------------------------------------------------

    @Test
    void addStockCreatesPositionWithNormalizedTicker() {
        portfolio.addStock(" meta ", 10, bd("500"));

        Stock stock = portfolio.findStock("META").orElseThrow();
        assertEquals(10, stock.quantity());
        assertEquals(0, bd("500").compareTo(stock.averagePurchasePrice()));
    }

    @Test
    void addStockTwiceKeepsOnePositionWithWeightedAveragePrice() {
        portfolio.addStock("META", 100, bd("10"));
        portfolio.addStock("META", 100, bd("20"));

        assertEquals(1, portfolio.getStocks().size());
        Stock stock = portfolio.findStock("META").orElseThrow();
        assertEquals(200, stock.quantity());
        assertEquals(0, bd("15").compareTo(stock.averagePurchasePrice()));
    }

    @Test
    void addStockRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> portfolio.addStock("META", 0, bd("5")));
        assertThrows(IllegalArgumentException.class, () -> portfolio.addStock("META", 1, bd("-5")));
        assertThrows(IllegalArgumentException.class, () -> portfolio.addStock("not a ticker", 1, bd("5")));
        assertTrue(portfolio.getStocks().isEmpty());
    }

    @Test
    void findStockReturnsEmptyWhenNotHeld() {
        assertTrue(portfolio.findStock("AAPL").isEmpty());
    }

    @Test
    void getStocksIsSortedAndUnmodifiable() {
        portfolio.addStock("META", 1, bd("1"));
        portfolio.addStock("AAPL", 1, bd("1"));

        assertEquals(List.of("AAPL", "META"), portfolio.getStocks().stream().map(Stock::ticker).toList());
        Set<Stock> view = portfolio.getStocks();
        Stock extra = new Stock("TSLA", 1, bd("1"));
        assertThrows(UnsupportedOperationException.class, () -> view.add(extra));
    }

    // ---- selling -----------------------------------------------------------

    @Test
    void sellStockReducesPositionKeepingAveragePrice() {
        portfolio.addStock("META", 10, bd("500"));

        portfolio.sellStock("meta", 4);

        Stock stock = portfolio.findStock("META").orElseThrow();
        assertEquals(6, stock.quantity());
        assertEquals(0, bd("500").compareTo(stock.averagePurchasePrice()));
    }

    @Test
    void sellingEverythingRemovesThePosition() {
        portfolio.addStock("META", 10, bd("500"));

        portfolio.sellStock("META", 10);

        assertTrue(portfolio.findStock("META").isEmpty());
        assertTrue(portfolio.getStocks().isEmpty());
    }

    @Test
    void sellMoreThanHeldIsRejected() {
        portfolio.addStock("META", 10, bd("500"));

        InsufficientQuantityException e =
                assertThrows(InsufficientQuantityException.class, () -> portfolio.sellStock("META", 11));

        assertTrue(e.getMessage().contains("only 10 held"));
        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
    }

    @Test
    void sellStockNotHeldIsRejected() {
        assertThrows(InsufficientQuantityException.class, () -> portfolio.sellStock("META", 1));
    }

    @Test
    void sellNonPositiveQuantityIsRejected() {
        portfolio.addStock("META", 10, bd("500"));
        assertThrows(IllegalArgumentException.class, () -> portfolio.sellStock("META", 0));
        assertThrows(IllegalArgumentException.class, () -> portfolio.sellStock("META", -3));
    }

    // ---- target allocation -------------------------------------------------

    @Test
    void setTargetAllocationReplacesPreviousOne() {
        TargetAllocation first = target("META", "40", "AAPL", "60");
        TargetAllocation second = target("META", "50", "AAPL", "50");

        portfolio.setTargetAllocation(first);
        portfolio.setTargetAllocation(second);

        assertEquals(second, portfolio.getTargetAllocation().orElseThrow());
    }

    @Test
    void setTargetAllocationRejectsNull() {
        assertThrows(NullPointerException.class, () -> portfolio.setTargetAllocation(null));
    }

    // ---- valuation ---------------------------------------------------------

    @Test
    void totalValueSumsQuantityTimesMarketPrice() {
        portfolio.addStock("META", 10, bd("1"));
        portfolio.addStock("AAPL", 50, bd("1"));
        when(prices.getPrice("META")).thenReturn(bd("500"));
        when(prices.getPrice("AAPL")).thenReturn(bd("190"));

        assertEquals(0, bd("14500").compareTo(portfolio.getTotalValue(prices)));
    }

    @Test
    void totalValueOfEmptyPortfolioIsZeroWithoutAskingForPrices() {
        assertEquals(0, BigDecimal.ZERO.compareTo(portfolio.getTotalValue(prices)));
        verifyNoInteractions(prices);
    }

    @Test
    void currentAllocationIsPercentageOfMarketValue() {
        portfolio.addStock("META", 10, bd("1"));
        portfolio.addStock("AAPL", 50, bd("1"));
        when(prices.getPrice("META")).thenReturn(bd("500"));
        when(prices.getPrice("AAPL")).thenReturn(bd("190"));

        Map<String, BigDecimal> allocation = portfolio.getCurrentAllocation(prices);

        assertEquals(List.of("AAPL", "META"), List.copyOf(allocation.keySet()));
        assertEquals(bd("65.52"), allocation.get("AAPL"));
        assertEquals(bd("34.48"), allocation.get("META"));
    }

    @Test
    void currentAllocationOfEmptyPortfolioIsEmpty() {
        assertTrue(portfolio.getCurrentAllocation(prices).isEmpty());
        verifyNoInteractions(prices);
    }

    @Test
    void valuationPropagatesMissingPrices() {
        portfolio.addStock("META", 10, bd("1"));
        when(prices.getPrice("META")).thenThrow(new PriceUnavailableException("META"));

        assertThrows(PriceUnavailableException.class, () -> portfolio.getCurrentAllocation(prices));
    }

    // ---- rebalancing -------------------------------------------------------

    @Test
    void rebalanceWithoutTargetFailsAndNeverCallsStrategy() {
        portfolio.addStock("META", 10, bd("500"));

        assertThrows(InvalidAllocationException.class, () -> portfolio.rebalance(prices));

        verify(strategy, never()).plan(any(), any(), any());
    }

    @Test
    void rebalanceDelegatesToStrategyWithHoldingsTargetAndPrices() {
        portfolio.addStock("META", 10, bd("500"));
        TargetAllocation target = target("META", "40", "AAPL", "60");
        portfolio.setTargetAllocation(target);
        RebalancePlan expected = new RebalancePlan(List.of(new TradeAction("AAPL", TradeSide.BUY, 5, bd("190"))));
        when(strategy.plan(portfolio.getStocks(), target, prices)).thenReturn(expected);

        RebalancePlan plan = portfolio.rebalance(prices);

        assertEquals(expected, plan);
        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity(), "rebalance() must not mutate");
    }

    @Test
    void applyRebalanceSellsThenBuys() {
        portfolio.addStock("META", 10, bd("500"));
        portfolio.addStock("AAPL", 50, bd("190"));
        RebalancePlan plan = new RebalancePlan(List.of(
                new TradeAction("AAPL", TradeSide.SELL, 4, bd("190")),
                new TradeAction("META", TradeSide.BUY, 1, bd("500")),
                new TradeAction("TSLA", TradeSide.BUY, 2, bd("175"))));

        portfolio.applyRebalance(plan);

        assertEquals(46, portfolio.findStock("AAPL").orElseThrow().quantity());
        assertEquals(11, portfolio.findStock("META").orElseThrow().quantity());
        assertEquals(2, portfolio.findStock("TSLA").orElseThrow().quantity());
    }

    @Test
    void applyRebalanceThatCannotBeFulfilledLeavesPortfolioUntouched() {
        portfolio.addStock("META", 10, bd("500"));
        RebalancePlan plan = new RebalancePlan(List.of(
                new TradeAction("META", TradeSide.SELL, 3, bd("500")),
                new TradeAction("AAPL", TradeSide.SELL, 1, bd("190"))));

        assertThrows(InsufficientQuantityException.class, () -> portfolio.applyRebalance(plan));

        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
        assertFalse(portfolio.findStock("AAPL").isPresent());
    }

    // ---- snapshot ----------------------------------------------------------

    @Test
    void snapshotWithoutTargetHasEmptyAllocation() {
        portfolio.addStock("META", 10, bd("500"));

        PortfolioSnapshot snapshot = portfolio.snapshot();

        assertEquals("acc-1", snapshot.accountId());
        assertEquals(1, snapshot.stocks().size());
        assertTrue(snapshot.targetAllocation().isEmpty());
    }

    @Test
    void snapshotIsADetachedCopy() {
        portfolio.addStock("META", 10, bd("500"));
        portfolio.setTargetAllocation(target("META", "40", "AAPL", "60"));

        PortfolioSnapshot snapshot = portfolio.snapshot();
        portfolio.sellStock("META", 10);

        assertEquals(1, snapshot.stocks().size());
        assertEquals(2, snapshot.targetAllocation().size());
        assertEquals(0, bd("40").compareTo(snapshot.targetAllocation().get("META")));
    }
}
