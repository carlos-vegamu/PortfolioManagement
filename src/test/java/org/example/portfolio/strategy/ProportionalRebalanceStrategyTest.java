package org.example.portfolio.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.example.portfolio.domain.MarketPrices;
import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.Stock;
import org.example.portfolio.domain.TargetAllocation;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.domain.TradeSide;
import org.example.portfolio.exception.PriceUnavailableException;
import org.junit.jupiter.api.Test;

class ProportionalRebalanceStrategyTest {

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "META", bd("500"), "AAPL", bd("190"), "TSLA", bd("175"), "NVDA", bd("900"),
            "AMZN", bd("300"), "MSFT", bd("350"));

    private final ProportionalRebalanceStrategy strategy = new ProportionalRebalanceStrategy();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static SortedMap<String, Stock> holdings(Object... tickerQuantityPairs) {
        SortedMap<String, Stock> holdings = new TreeMap<>();
        for (int i = 0; i < tickerQuantityPairs.length; i += 2) {
            String ticker = (String) tickerQuantityPairs[i];
            holdings.put(ticker, new Stock(ticker, ((Number) tickerQuantityPairs[i + 1]).longValue(), BigDecimal.ONE));
        }
        return holdings;
    }

    private static TargetAllocation target(Object... tickerPercentPairs) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (int i = 0; i < tickerPercentPairs.length; i += 2) {
            map.put((String) tickerPercentPairs[i], bd((String) tickerPercentPairs[i + 1]));
        }
        return TargetAllocation.of(map);
    }

    private RebalancePlan plan(SortedMap<String, Stock> holdings, String cash, TargetAllocation target) {
        return strategy.plan(holdings, bd(cash), target, MarketPrices.of(PRICES));
    }

    private static TradeAction sell(String ticker, long quantity) {
        return new TradeAction(ticker, TradeSide.SELL, quantity, PRICES.get(ticker));
    }

    private static TradeAction buy(String ticker, long quantity) {
        return new TradeAction(ticker, TradeSide.BUY, quantity, PRICES.get(ticker));
    }

    private static BigDecimal netCash(RebalancePlan plan) {
        return plan.proceeds().subtract(plan.cost());
    }

    @Test
    void sellsOverweightAndBuysUnderweightKeepingTheRemainderAsCash() {
        // META 5,000 + AAPL 9,500 = 14,500; target 40/60 => META 5,800 (11 shares), AAPL 8,700 (45.8 shares).
        // 11 META + 46 AAPL leave 260, which keeps one more AAPL instead of selling it: 70 stays as cash.
        RebalancePlan plan = plan(holdings("META", 10, "AAPL", 50), "0", target("META", "40", "AAPL", "60"));

        assertEquals(List.of(sell("AAPL", 3)), plan.sells());
        assertEquals(List.of(buy("META", 1)), plan.buys());
        assertEquals(0, bd("70").compareTo(netCash(plan)));
    }

    @Test
    void sellsHoldingsThatAreNotInTheTargetAndBuysNewOnes() {
        // TSLA 1,750 is all there is; 100% AAPL => sell all 10 TSLA, buy floor(1750/190)=9 AAPL, keep 40
        RebalancePlan plan = plan(holdings("TSLA", 10), "0", target("AAPL", "100"));

        assertEquals(List.of(sell("TSLA", 10)), plan.sells());
        assertEquals(List.of(buy("AAPL", 9)), plan.buys());
        assertEquals(0, bd("40").compareTo(netCash(plan)));
    }

    @Test
    void holdingsMissingFromTheTargetAreSoldEvenWhenNothingCanBeBoughtWithTheProceeds() {
        // AAPL 1,900 + TSLA 175 = 2,075, all for AAPL: 10 AAPL already held, 175 cannot buy an 11th
        RebalancePlan plan = plan(holdings("AAPL", 10, "TSLA", 1), "0", target("AAPL", "100"));

        assertEquals(List.of(sell("TSLA", 1)), plan.sells());
        assertTrue(plan.buys().isEmpty());
    }

    @Test
    void alreadyBalancedPortfolioNeedsNoTrades() {
        // META 10 x 500 = 5,000 and AMZN 17 x 300 = 5,100 -> as close to 50/50 as whole shares get
        RebalancePlan plan = plan(holdings("META", 10, "AMZN", 17), "0", target("META", "50", "AMZN", "50"));

        assertTrue(plan.isEmpty());
    }

    @Test
    void differencesSmallerThanOneShareProduceNoOrder() {
        // 10 META (5,000) + 1 AAPL (190): target 96/4 => AAPL target 207.6 -> +0.09 share
        RebalancePlan plan = plan(holdings("META", 10, "AAPL", 1), "0", target("META", "96", "AAPL", "4"));

        assertTrue(plan.isEmpty());
    }

    @Test
    void emptyPortfolioWithoutCashYieldsEmptyPlanWithoutPriceLookups() {
        @SuppressWarnings("unchecked")
        Function<String, BigDecimal> source = mock(Function.class);

        RebalancePlan plan = strategy.plan(holdings(), BigDecimal.ZERO, target("META", "100"), MarketPrices.from(source));

        assertTrue(plan.isEmpty());
        verifyNoInteractions(source);
    }

    @Test
    void cashAloneIsInvested() {
        RebalancePlan plan = plan(holdings(), "1100", target("META", "100"));

        assertEquals(List.of(buy("META", 2)), plan.buys());
        assertEquals(0, bd("-1000").compareTo(netCash(plan)));
    }

    @Test
    void leftoverCashBuysAnExtraShareOfTheLargestGapFirst() {
        // 1,000 cash, 50/50: AMZN gets 1 share (gap 200), MSFT 1 share (gap 150), 350 left.
        // That buys a second AMZN (largest gap); the 50 left cannot buy anything else.
        RebalancePlan plan = plan(holdings(), "1000", target("AMZN", "50", "MSFT", "50"));

        assertEquals(List.of(buy("AMZN", 2), buy("MSFT", 1)), plan.buys());
        assertEquals(0, bd("-950").compareTo(netCash(plan)));
    }

    @Test
    void neverSellsSharesOnlyToHoldTheProceedsAsCash() {
        // 3 AAPL = 570, 50/50 with NVDA at 900: no NVDA is affordable, so selling AAPL would only park cash.
        // The previous algorithm sold 2 AAPL, then the last one on the next run, and the portfolio ended empty.
        RebalancePlan plan = plan(holdings("AAPL", 3), "0", target("AAPL", "50", "NVDA", "50"));

        assertTrue(plan.isEmpty());
    }

    @Test
    void sellsOnlyWhatTheBuysNeed() {
        // 15 AAPL = 2,850, 50/50 with NVDA: selling 5 AAPL pays for 1 NVDA and keeps 50 as cash.
        // The previous algorithm sold 8 AAPL for the same single NVDA and lost the other 620.
        RebalancePlan plan = plan(holdings("AAPL", 15), "0", target("AAPL", "50", "NVDA", "50"));

        assertEquals(List.of(sell("AAPL", 5)), plan.sells());
        assertEquals(List.of(buy("NVDA", 1)), plan.buys());
        assertEquals(0, bd("50").compareTo(netCash(plan)));
    }

    @Test
    void eachPriceIsLookedUpOnce() {
        @SuppressWarnings("unchecked")
        Function<String, BigDecimal> source = mock(Function.class);
        when(source.apply("META")).thenReturn(bd("500"));
        when(source.apply("AAPL")).thenReturn(bd("190"));

        strategy.plan(holdings("META", 10, "AAPL", 50), BigDecimal.ZERO, target("META", "40", "AAPL", "60"),
                MarketPrices.from(source));

        verify(source, times(1)).apply("META");
        verify(source, times(1)).apply("AAPL");
    }

    @Test
    void missingPriceIsPropagated() {
        assertThrows(PriceUnavailableException.class,
                () -> plan(holdings("META", 1), "0", target("META", "50", "GOOGL", "50")));
    }

    @Test
    void randomPortfoliosKeepTheirValueAndAreBalancedAfterOneRebalance() {
        Random random = new Random(20261008);
        List<String> universe = List.of("AAPL", "AMZN", "GOOGL", "META", "MSFT", "NVDA", "TSLA");
        for (int run = 0; run < 500; run++) {
            Map<String, BigDecimal> priceTable = new HashMap<>();
            universe.forEach(t -> priceTable.put(t, BigDecimal.valueOf(100 + random.nextInt(99_900), 2)));
            MarketPrices prices = MarketPrices.of(priceTable);
            Portfolio portfolio = new Portfolio("acc");
            for (String ticker : universe) {
                if (random.nextInt(3) == 0) {
                    portfolio = portfolio.addStock(ticker, 1 + random.nextInt(500), BigDecimal.ONE);
                }
            }
            // later rounds start from the cash the earlier ones left behind
            for (int round = 0; round < 3; round++) {
                portfolio = portfolio.withTargetAllocation(randomTarget(random, universe));
                String context = "run " + run + " round " + round + ": " + portfolio.getStocks()
                        + " cash " + portfolio.getCash() + " target " + portfolio.getTargetAllocation().orElseThrow();

                BigDecimal valueBefore = portfolio.getTotalValue(prices);
                RebalancePlan plan = portfolio.rebalance(strategy, prices);
                Portfolio after = portfolio.applyRebalance(plan);

                assertEquals(0, valueBefore.compareTo(after.getTotalValue(prices)), "value changed in " + context);
                assertTrue(after.rebalance(strategy, prices).isEmpty(), "second rebalance not empty in " + context);
                assertNothingAffordableLeftUndone(after, plan, prices, context);
                portfolio = after;
            }
        }
    }

    /** No underweight target stock could take one more share, and no target stock was sold just to keep cash. */
    private static void assertNothingAffordableLeftUndone(Portfolio after, RebalancePlan plan, MarketPrices prices,
                                                          String context) {
        BigDecimal total = after.getTotalValue(prices);
        after.getTargetAllocation().orElseThrow().asMap().forEach((ticker, pct) -> {
            BigDecimal price = prices.priceOf(ticker);
            long held = after.findStock(ticker).map(Stock::quantity).orElse(0L);
            BigDecimal gap = total.multiply(pct).movePointLeft(2).subtract(price.multiply(BigDecimal.valueOf(held)));
            if (gap.signum() > 0) {
                assertTrue(price.compareTo(after.getCash()) > 0, ticker + " underweight but affordable in " + context);
            }
        });
        for (TradeAction sell : plan.sells()) {
            if (after.getTargetAllocation().orElseThrow().tickers().contains(sell.ticker())) {
                assertTrue(sell.price().compareTo(after.getCash()) > 0, sell + " only raised idle cash in " + context);
            }
        }
    }

    /** 1 to 4 tickers whose percentages (two decimals) add up to exactly 100. */
    private static TargetAllocation randomTarget(Random random, List<String> universe) {
        List<String> shuffled = new ArrayList<>(universe);
        Collections.shuffle(shuffled, random);
        int size = 1 + random.nextInt(4);
        TreeSet<Integer> cuts = new TreeSet<>();
        while (cuts.size() < size - 1) {
            cuts.add(1 + random.nextInt(9_999));
        }
        cuts.add(10_000);
        Map<String, BigDecimal> percentages = new HashMap<>();
        int previous = 0;
        int i = 0;
        for (int cut : cuts) {
            percentages.put(shuffled.get(i++), BigDecimal.valueOf(cut - previous, 2));
            previous = cut;
        }
        return TargetAllocation.of(percentages);
    }
}
