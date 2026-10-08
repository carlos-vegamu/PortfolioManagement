package org.example.portfolio.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Function;

import org.example.portfolio.exception.InsufficientCashException;
import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;
import org.example.portfolio.exception.PriceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioTest {

    @Mock
    private RebalanceStrategy strategy;
    @Mock
    private Function<String, BigDecimal> priceSource;

    private final Portfolio empty = new Portfolio("acc-1");

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static TargetAllocation target(String t1, String p1, String t2, String p2) {
        return TargetAllocation.of(Map.of(t1, bd(p1), t2, bd(p2)));
    }

    private static TradeAction sell(String ticker, long quantity, String price) {
        return new TradeAction(ticker, TradeSide.SELL, quantity, bd(price));
    }

    private static TradeAction buy(String ticker, long quantity, String price) {
        return new TradeAction(ticker, TradeSide.BUY, quantity, bd(price));
    }

    private MarketPrices prices() {
        return MarketPrices.from(priceSource);
    }

    /** 10 META held and 1,000 in cash, left by a rebalance that sold 2 META at 500. */
    private Portfolio metaWithCash() {
        Portfolio portfolio = empty.addStock("META", 12, bd("500"))
                .applyRebalance(new RebalancePlan(List.of(sell("META", 2, "500")), List.of()));
        assertEquals(0, bd("1000").compareTo(portfolio.getCash()));
        return portfolio;
    }

    // ---- construction ------------------------------------------------------

    @Test
    void constructorTrimsAccountId() {
        assertEquals("acc-9", new Portfolio("  acc-9 ").getAccountId());
    }

    @Test
    void constructorRejectsBlankAccountId() {
        assertThrows(IllegalArgumentException.class, () -> new Portfolio(" "));
        assertThrows(IllegalArgumentException.class, () -> new Portfolio(null));
    }

    @Test
    void newPortfolioIsEmptyWithoutCashOrTarget() {
        assertTrue(empty.getStocks().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getCash()));
        assertTrue(empty.getTargetAllocation().isEmpty());
    }

    // ---- buying ------------------------------------------------------------

    @Test
    void addStockCreatesPositionWithNormalizedTicker() {
        Portfolio portfolio = empty.addStock(" meta ", 10, bd("500"));

        Stock stock = portfolio.findStock("META").orElseThrow();
        assertEquals(10, stock.quantity());
        assertEquals(0, bd("500").compareTo(stock.averagePurchasePrice()));
    }

    @Test
    void addStockTwiceKeepsOnePositionWithWeightedAveragePrice() {
        Portfolio portfolio = empty.addStock("META", 100, bd("10")).addStock("META", 100, bd("20"));

        assertEquals(1, portfolio.getStocks().size());
        Stock stock = portfolio.findStock("META").orElseThrow();
        assertEquals(200, stock.quantity());
        assertEquals(0, bd("15").compareTo(stock.averagePurchasePrice()));
    }

    @Test
    void addStockRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> empty.addStock("META", 0, bd("5")));
        assertThrows(IllegalArgumentException.class, () -> empty.addStock("META", 1, bd("-5")));
        assertThrows(IllegalArgumentException.class, () -> empty.addStock("not a ticker", 1, bd("5")));
    }

    @Test
    void changesReturnANewPortfolioAndLeaveTheOriginalUntouched() {
        Portfolio bought = empty.addStock("META", 10, bd("500"));
        Portfolio sold = bought.sellStock("META", 4);
        Portfolio targeted = sold.withTargetAllocation(target("META", "40", "AAPL", "60"));

        assertTrue(empty.getStocks().isEmpty());
        assertEquals(10, bought.findStock("META").orElseThrow().quantity());
        assertEquals(6, sold.findStock("META").orElseThrow().quantity());
        assertTrue(sold.getTargetAllocation().isEmpty());
        assertEquals("acc-1", targeted.getAccountId());
        assertSame(sold.getStocks(), targeted.getStocks(), "unchanged positions are shared, not copied");
    }

    @Test
    void manualTradesDoNotTouchTheCash() {
        Portfolio portfolio = metaWithCash().addStock("AAPL", 3, bd("190")).sellStock("META", 1);

        assertEquals(0, bd("1000").compareTo(portfolio.getCash()));
    }

    @Test
    void findStockReturnsEmptyWhenNotHeld() {
        assertTrue(empty.findStock("AAPL").isEmpty());
    }

    @Test
    void getStocksIsSortedAndUnmodifiable() {
        Portfolio portfolio = empty.addStock("META", 1, bd("1")).addStock("AAPL", 1, bd("1"));

        assertEquals(List.of("AAPL", "META"), List.copyOf(portfolio.getStocks().keySet()));
        SortedMap<String, Stock> view = portfolio.getStocks();
        Stock extra = new Stock("TSLA", 1, bd("1"));
        assertThrows(UnsupportedOperationException.class, () -> view.put("TSLA", extra));
    }

    // ---- selling -----------------------------------------------------------

    @Test
    void sellStockReducesPositionKeepingAveragePrice() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500")).sellStock("meta", 4);

        Stock stock = portfolio.findStock("META").orElseThrow();
        assertEquals(6, stock.quantity());
        assertEquals(0, bd("500").compareTo(stock.averagePurchasePrice()));
    }

    @Test
    void sellingEverythingRemovesThePosition() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500")).sellStock("META", 10);

        assertTrue(portfolio.findStock("META").isEmpty());
        assertTrue(portfolio.getStocks().isEmpty());
    }

    @Test
    void sellMoreThanHeldIsRejected() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500"));

        InsufficientQuantityException e =
                assertThrows(InsufficientQuantityException.class, () -> portfolio.sellStock("META", 11));

        assertTrue(e.getMessage().contains("only 10 held"));
        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
    }

    @Test
    void sellStockNotHeldIsRejected() {
        assertThrows(InsufficientQuantityException.class, () -> empty.sellStock("META", 1));
    }

    @Test
    void sellNonPositiveQuantityIsRejected() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500"));
        assertThrows(IllegalArgumentException.class, () -> portfolio.sellStock("META", 0));
        assertThrows(IllegalArgumentException.class, () -> portfolio.sellStock("META", -3));
    }

    // ---- target allocation -------------------------------------------------

    @Test
    void withTargetAllocationReplacesPreviousOne() {
        TargetAllocation second = target("META", "50", "AAPL", "50");

        Portfolio portfolio = empty.withTargetAllocation(target("META", "40", "AAPL", "60")).withTargetAllocation(second);

        assertEquals(second, portfolio.getTargetAllocation().orElseThrow());
    }

    @Test
    void withTargetAllocationRejectsNull() {
        assertThrows(NullPointerException.class, () -> empty.withTargetAllocation(null));
    }

    // ---- valuation ---------------------------------------------------------

    @Test
    void totalValueIsMarketValueOfThePositionsPlusCash() {
        Portfolio portfolio = metaWithCash().addStock("AAPL", 50, bd("1"));
        when(priceSource.apply("META")).thenReturn(bd("500"));
        when(priceSource.apply("AAPL")).thenReturn(bd("190"));

        // 10 x 500 + 50 x 190 + 1,000 cash
        assertEquals(0, bd("15500").compareTo(portfolio.getTotalValue(prices())));
    }

    @Test
    void totalValueOfEmptyPortfolioIsZeroWithoutAskingForPrices() {
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.getTotalValue(prices())));
        verifyNoInteractions(priceSource);
    }

    @Test
    void currentAllocationIsPercentageOfMarketValue() {
        Portfolio portfolio = empty.addStock("META", 10, bd("1")).addStock("AAPL", 50, bd("1"));
        when(priceSource.apply("META")).thenReturn(bd("500"));
        when(priceSource.apply("AAPL")).thenReturn(bd("190"));

        AllocationReport report = portfolio.getCurrentAllocation(prices());

        assertEquals(List.of("AAPL", "META"), List.copyOf(report.current().keySet()));
        assertEquals(bd("65.52"), report.current().get("AAPL"));
        assertEquals(bd("34.48"), report.current().get("META"));
        assertEquals(bd("0.00"), report.cashPercentage());
        assertTrue(report.target().isEmpty());
    }

    @Test
    void currentAllocationCountsCashAndCarriesTheTarget() {
        TargetAllocation target = target("META", "40", "AAPL", "60");
        Portfolio portfolio = metaWithCash().withTargetAllocation(target);
        when(priceSource.apply("META")).thenReturn(bd("500"));

        AllocationReport report = portfolio.getCurrentAllocation(prices());

        // 5,000 in META and 1,000 in cash
        assertEquals(bd("83.33"), report.current().get("META"));
        assertEquals(bd("16.67"), report.cashPercentage());
        assertEquals(target, report.target().orElseThrow());
    }

    @Test
    void currentAllocationReadsEachPriceOnce() {
        Portfolio portfolio = empty.addStock("META", 10, bd("1"));
        when(priceSource.apply("META")).thenReturn(bd("500"));

        portfolio.getCurrentAllocation(prices());

        verify(priceSource, times(1)).apply("META");
    }

    @Test
    void currentAllocationOfEmptyPortfolioIsEmpty() {
        AllocationReport report = empty.getCurrentAllocation(prices());

        assertTrue(report.current().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(report.cashPercentage()));
        verifyNoInteractions(priceSource);
    }

    @Test
    void valuationPropagatesMissingPrices() {
        Portfolio portfolio = empty.addStock("META", 10, bd("1"));
        when(priceSource.apply("META")).thenThrow(new PriceUnavailableException("META"));

        assertThrows(PriceUnavailableException.class, () -> portfolio.getCurrentAllocation(prices()));
    }

    // ---- rebalancing -------------------------------------------------------

    @Test
    void rebalanceWithoutTargetFailsAndNeverCallsStrategy() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500"));

        assertThrows(InvalidAllocationException.class, () -> portfolio.rebalance(strategy, prices()));

        verify(strategy, never()).plan(any(), any(), any(), any());
    }

    @Test
    void rebalanceRequiresAStrategy() {
        assertThrows(NullPointerException.class, () -> empty.rebalance(null, prices()));
    }

    @Test
    void rebalanceDelegatesToStrategyWithHoldingsCashTargetAndPrices() {
        TargetAllocation target = target("META", "40", "AAPL", "60");
        Portfolio portfolio = metaWithCash().withTargetAllocation(target);
        MarketPrices prices = prices();
        RebalancePlan expected = new RebalancePlan(List.of(), List.of(buy("AAPL", 5, "190")));
        when(strategy.plan(portfolio.getStocks(), portfolio.getCash(), target, prices)).thenReturn(expected);

        RebalancePlan plan = portfolio.rebalance(strategy, prices);

        assertEquals(expected, plan);
        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity(), "rebalance() must not change anything");
    }

    @Test
    void applyRebalanceSellsThenBuysThroughTheCash() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500")).addStock("AAPL", 50, bd("190"));
        RebalancePlan plan = new RebalancePlan(
                List.of(sell("AAPL", 4, "190")),
                List.of(buy("META", 1, "500"), buy("TSLA", 1, "175")));

        Portfolio after = portfolio.applyRebalance(plan);

        assertEquals(46, after.findStock("AAPL").orElseThrow().quantity());
        assertEquals(11, after.findStock("META").orElseThrow().quantity());
        assertEquals(1, after.findStock("TSLA").orElseThrow().quantity());
        // 760 raised, 675 spent
        assertEquals(0, bd("85").compareTo(after.getCash()));
        assertEquals(50, portfolio.findStock("AAPL").orElseThrow().quantity(), "the original is untouched");
    }

    @Test
    void applyRebalanceCanSpendCashLeftByAnEarlierRebalance() {
        Portfolio after = metaWithCash().applyRebalance(new RebalancePlan(List.of(), List.of(buy("META", 2, "500"))));

        assertEquals(12, after.findStock("META").orElseThrow().quantity());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getCash()));
    }

    @Test
    void applyRebalanceChecksTheTotalSoldPerTickerBeforeChangingAnything() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500"));
        RebalancePlan plan = new RebalancePlan(List.of(sell("META", 6, "500"), sell("META", 6, "500")), List.of());

        InsufficientQuantityException e =
                assertThrows(InsufficientQuantityException.class, () -> portfolio.applyRebalance(plan));

        assertTrue(e.getMessage().contains("Cannot sell 12 META: only 10 held"), e.getMessage());
        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
    }

    @Test
    void applyRebalanceRejectsSellsOfTickersNotHeld() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500"));
        RebalancePlan plan = new RebalancePlan(List.of(sell("META", 3, "500"), sell("AAPL", 1, "190")), List.of());

        assertThrows(InsufficientQuantityException.class, () -> portfolio.applyRebalance(plan));

        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
        assertFalse(portfolio.findStock("AAPL").isPresent());
    }

    @Test
    void applyRebalanceRejectsBuysTheCashAndProceedsCannotPayFor() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500"));
        RebalancePlan plan = new RebalancePlan(List.of(sell("META", 1, "500")), List.of(buy("AAPL", 3, "190")));

        InsufficientCashException e = assertThrows(InsufficientCashException.class, () -> portfolio.applyRebalance(plan));

        assertTrue(e.getMessage().contains("570"), e.getMessage());
        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
    }

    @Test
    void applyRebalanceFailingHalfWayLeavesThePortfolioUntouched() {
        Portfolio portfolio = empty.addStock("AAPL", 1, bd("190")).addStock("META", Long.MAX_VALUE, bd("1"));
        RebalancePlan overflowing = new RebalancePlan(List.of(sell("AAPL", 1, "190")), List.of(buy("META", 1, "1")));

        assertThrows(ArithmeticException.class, () -> portfolio.applyRebalance(overflowing));

        assertEquals(1, portfolio.findStock("AAPL").orElseThrow().quantity());
        assertEquals(0, BigDecimal.ZERO.compareTo(portfolio.getCash()));
    }

    @Test
    void applyingAnEmptyPlanReturnsTheSamePortfolio() {
        Portfolio portfolio = empty.addStock("META", 10, bd("500"));

        assertSame(portfolio, portfolio.applyRebalance(RebalancePlan.empty()));
    }

    // ---- snapshot ----------------------------------------------------------

    @Test
    void snapshotWithoutTargetHasNoAllocation() {
        PortfolioSnapshot snapshot = empty.addStock("META", 10, bd("500")).snapshot();

        assertEquals("acc-1", snapshot.accountId());
        assertEquals(1, snapshot.stocks().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(snapshot.cash()));
        assertTrue(snapshot.targetAllocation().isEmpty());
    }

    @Test
    void snapshotCarriesCashAndTargetAndCannotBeModified() {
        TargetAllocation target = target("META", "40", "AAPL", "60");
        PortfolioSnapshot snapshot = metaWithCash().withTargetAllocation(target).snapshot();

        assertEquals(0, bd("1000").compareTo(snapshot.cash()));
        assertEquals(target, snapshot.targetAllocation().orElseThrow());
        SortedMap<String, Stock> stocks = snapshot.stocks();
        assertThrows(UnsupportedOperationException.class, () -> stocks.remove("META"));
    }
}
