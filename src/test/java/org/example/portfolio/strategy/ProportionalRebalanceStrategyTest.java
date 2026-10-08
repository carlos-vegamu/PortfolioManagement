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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
    void leftoverCashBuysTheExtraShareThatClosesTheMostGap() {
        // 1,000 cash, 50/50: AMZN gets 1 share (gap 200), MSFT 1 share (gap 150), 350 left.
        // It affords one more share of either; AMZN closes more. The 50 left buys nothing else.
        RebalancePlan plan = plan(holdings(), "1000", target("AMZN", "50", "MSFT", "50"));

        assertEquals(List.of(buy("AMZN", 2), buy("MSFT", 1)), plan.buys());
        assertEquals(0, bd("-950").compareTo(netCash(plan)));
    }

    @Test
    void twoCheaperSharesBeatOneExpensiveShareThatOvershoots() {
        // 5 AAPL = 950 for META 33 / MSFT 33 / NVDA 34: none affords a whole share at first.
        // The largest gap (NVDA, 323) would take one 900 share: 94.7% NVDA, drift 1,254.
        // META + MSFT (gaps 313.5 each, 850 together) leave a drift of 646 instead.
        RebalancePlan plan = plan(holdings("AAPL", 5), "0", target("META", "33", "MSFT", "33", "NVDA", "34"));

        assertEquals(List.of(sell("AAPL", 5)), plan.sells());
        assertEquals(List.of(buy("META", 1), buy("MSFT", 1)), plan.buys());
        assertEquals(0, bd("100").compareTo(netCash(plan)));
    }

    @Test
    void spreadsTheLeftoverWhenThatReachesTheTargetMoreClosely() {
        // 10,000 cash for 44 / 28 / 28 at 1,000 / 500 / 500: 4 + 5 + 5 shares leave 1,000.
        // A 5th of the first (largest gap, 400) gives 50/25/25; a 6th of each other gives 40/30/30.
        Map<String, BigDecimal> prices = Map.of("GOOGL", bd("1000"), "AMZN", bd("500"), "MSFT", bd("500"));
        RebalancePlan plan = strategy.plan(holdings(), bd("10000"),
                target("GOOGL", "44", "AMZN", "28", "MSFT", "28"), MarketPrices.of(prices));

        assertEquals(List.of(
                new TradeAction("AMZN", TradeSide.BUY, 6, bd("500")),
                new TradeAction("GOOGL", TradeSide.BUY, 4, bd("1000")),
                new TradeAction("MSFT", TradeSide.BUY, 6, bd("500"))), plan.buys());
    }

    @Test
    void findsTheBestCombinationEvenWhereTakingTheBestValueSharesFirstFails() {
        // 10,000 cash for AAA 0.95% @100, BBB 9% @1,000, DDD 90.05% @5,000: 1 DDD leaves 5,000 and
        // gaps of 95, 900 and 4,005. By gap per price, AAA then BBB would close 995 and block DDD;
        // a second DDD closes 4,005.
        Map<String, BigDecimal> prices = Map.of("AAA", bd("100"), "BBB", bd("1000"), "DDD", bd("5000"));
        RebalancePlan plan = strategy.plan(holdings(), bd("10000"),
                target("AAA", "0.95", "BBB", "9", "DDD", "90.05"), MarketPrices.of(prices));

        assertEquals(List.of(new TradeAction("DDD", TradeSide.BUY, 2, bd("5000"))), plan.buys());
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
                assertEquals(0, leastPossibleDrift(portfolio, prices).compareTo(drift(after, prices)),
                        "drift is not the least possible in " + context);
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

    /** Distance from the target: |value - target value| summed over every ticker, plus the cash. */
    private static BigDecimal drift(Portfolio portfolio, MarketPrices prices) {
        BigDecimal total = portfolio.getTotalValue(prices);
        TargetAllocation target = portfolio.getTargetAllocation().orElseThrow();
        Set<String> tickers = new TreeSet<>(target.tickers());
        tickers.addAll(portfolio.getStocks().keySet());
        BigDecimal drift = portfolio.getCash();
        for (String ticker : tickers) {
            long held = portfolio.findStock(ticker).map(Stock::quantity).orElse(0L);
            BigDecimal value = prices.priceOf(ticker).multiply(BigDecimal.valueOf(held));
            BigDecimal targetValue = total.multiply(target.asMap().getOrDefault(ticker, BigDecimal.ZERO)).movePointLeft(2);
            drift = drift.add(value.subtract(targetValue).abs());
        }
        return drift;
    }

    /**
     * Brute force over every set of target tickers that could get one share more than fits in
     * their target value: the least drift any whole-share rebalance of {@code portfolio} can reach.
     */
    private static BigDecimal leastPossibleDrift(Portfolio portfolio, MarketPrices prices) {
        BigDecimal total = portfolio.getTotalValue(prices);
        List<BigDecimal> shortfalls = new ArrayList<>();
        List<BigDecimal> sharePrices = new ArrayList<>();
        BigDecimal leftover = total;
        for (Map.Entry<String, BigDecimal> entry : portfolio.getTargetAllocation().orElseThrow().asMap().entrySet()) {
            BigDecimal price = prices.priceOf(entry.getKey());
            BigDecimal targetValue = total.multiply(entry.getValue()).movePointLeft(2);
            BigDecimal fitting = price.multiply(targetValue.divide(price, 0, RoundingMode.DOWN));
            leftover = leftover.subtract(fitting);
            shortfalls.add(targetValue.subtract(fitting));
            sharePrices.add(price);
        }
        BigDecimal least = null;
        for (int set = 0; set < 1 << shortfalls.size(); set++) {
            BigDecimal spent = BigDecimal.ZERO;
            BigDecimal drift = BigDecimal.ZERO;
            for (int i = 0; i < shortfalls.size(); i++) {
                boolean extraShare = (set & 1 << i) != 0;
                spent = spent.add(extraShare ? sharePrices.get(i) : BigDecimal.ZERO);
                drift = drift.add(extraShare ? sharePrices.get(i).subtract(shortfalls.get(i)) : shortfalls.get(i));
            }
            if (spent.compareTo(leftover) <= 0) {
                BigDecimal withCash = drift.add(leftover.subtract(spent));
                least = least == null || withCash.compareTo(least) < 0 ? withCash : least;
            }
        }
        return least;
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
