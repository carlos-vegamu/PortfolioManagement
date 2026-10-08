package org.example.portfolio.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

import org.example.portfolio.domain.MarketPrices;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.RebalanceStrategy;
import org.example.portfolio.domain.Stock;
import org.example.portfolio.domain.TargetAllocation;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.domain.TradeSide;

/**
 * Self-financing rebalancing in whole shares that never loses value: the market value of the
 * holdings plus the cash is redistributed according to the target percentages.
 *
 * <ol>
 *   <li>Each ticker gets as many whole shares as fit in its target value. Holdings missing from
 *       the target get none, so they are sold completely.</li>
 *   <li>The cash this leaves buys one more share of the underweight tickers whose gaps add up to
 *       the most it can afford (see {@code chooseExtraShares}).</li>
 *   <li>Cash still left cancels sells of targeted tickers, so shares are never sold only to sit
 *       in the portfolio as cash. This does not change the drift (the cash would count as drift
 *       instead) but saves trades, and it is why a ticker can stay more than one share over its
 *       target when no underweight ticker is affordable.</li>
 * </ol>
 *
 * <p>Drift is the total distance from the target: the sum, over every ticker and the cash, of
 * the difference between its value and its target value (the cash's target is zero). Among the
 * plans that keep at least the whole shares fitting each target (step 1), this one leaves the
 * least drift whenever the step-2 search runs to completion, which it does for typical portfolios
 * of a few dozen stocks (it stops at its step limit, and is skipped beyond
 * {@value #MAX_SEARCH_CANDIDATES} underweight tickers). Plans that give up such a share to fund
 * a share of another ticker are not considered, and they can do better: 2 META sold toward
 * NVDA 80% / AMZN 20% at 900 / 180 keeps 2 AMZN and 64% cash, where giving up the one AMZN
 * share that fits would fund an NVDA and leave 10% cash. A wider search is a planned follow-up.
 *
 * <p>Buys never cost more than the cash plus the sale proceeds, whatever is not spent stays in
 * the portfolio as cash, and planning again right after applying a plan, at the same prices,
 * gives an empty plan. An empty portfolio without cash gives an empty plan.
 * Runs in O(n log n) for n tickers held or targeted, plus a search bounded by the search limit
 * in steps of O(n), with one price lookup per ticker.
 */
public class ProportionalRebalanceStrategy implements RebalanceStrategy {

    /**
     * Steps the step-2 search may take by default. Exact for typical portfolios (a few dozen
     * stocks); many tickers at very mixed prices can need more, and then get the best set found.
     */
    static final int DEFAULT_SEARCH_LIMIT = 10_000;

    /** Beyond this many underweight tickers the search is skipped and shares go by gap per price. */
    static final int MAX_SEARCH_CANDIDATES = 1_000;

    /** Rounds the fractional part of the search bound up, so the bound never underestimates. */
    private static final MathContext BOUND_PRECISION = new MathContext(16, RoundingMode.CEILING);

    /** Most gap closed per unit of cash first, i.e. by gap / price, compared without dividing. */
    private static final Comparator<Position> MOST_GAP_PER_PRICE_FIRST = (a, b) -> {
        int byGapPerPrice = b.gap.multiply(a.price).compareTo(a.gap.multiply(b.price));
        return byGapPerPrice != 0 ? byGapPerPrice : a.ticker.compareTo(b.ticker);
    };

    private final int searchLimit;

    public ProportionalRebalanceStrategy() {
        this(DEFAULT_SEARCH_LIMIT);
    }

    /** @param searchLimit steps the step-2 search may take; tests use it to force the limit */
    ProportionalRebalanceStrategy(int searchLimit) {
        this.searchLimit = searchLimit;
    }

    @Override
    public RebalancePlan plan(SortedMap<String, Stock> holdings, BigDecimal cash, TargetAllocation target,
                              MarketPrices prices) {
        if (holdings.isEmpty() && cash.signum() == 0) {
            return RebalancePlan.empty();
        }

        Set<String> tickers = new TreeSet<>(holdings.keySet());
        tickers.addAll(target.tickers());
        List<Position> positions = new ArrayList<>(tickers.size());
        BigDecimal totalValue = cash;
        for (String ticker : tickers) {
            Stock stock = holdings.get(ticker);
            Position position = new Position(ticker, prices.priceOf(ticker), stock == null ? 0 : stock.quantity(),
                    target.asMap().getOrDefault(ticker, BigDecimal.ZERO));
            positions.add(position);
            totalValue = totalValue.add(position.valueOf(position.held));
        }

        // 1. as many whole shares as fit in each target value
        BigDecimal leftover = totalValue;
        List<Position> underweight = new ArrayList<>();
        for (Position position : positions) {
            BigDecimal targetValue = totalValue.multiply(position.percentage).movePointLeft(2);
            position.wanted = targetValue.divide(position.price, 0, RoundingMode.DOWN).longValueExact();
            BigDecimal wantedValue = position.valueOf(position.wanted);
            position.gap = targetValue.subtract(wantedValue);
            leftover = leftover.subtract(wantedValue);
            if (position.gap.signum() > 0) {
                underweight.add(position);
            }
        }

        // 2. one more share of the underweight tickers whose gaps add up to the most the leftover affords
        underweight.sort(MOST_GAP_PER_PRICE_FIRST);
        for (Position position : chooseExtraShares(underweight, leftover)) {
            position.wanted++;
            leftover = leftover.subtract(position.price);
        }

        // 3. keep targeted shares instead of selling them into idle cash
        for (Position position : positions) {
            if (position.percentage.signum() > 0 && position.wanted < position.held) {
                long affordable = leftover.divide(position.price, 0, RoundingMode.DOWN).longValueExact();
                long kept = Math.min(position.held - position.wanted, affordable);
                position.wanted += kept;
                leftover = leftover.subtract(position.valueOf(kept));
            }
        }

        List<TradeAction> sells = new ArrayList<>();
        List<TradeAction> buys = new ArrayList<>();
        for (Position position : positions) {
            long delta = position.wanted - position.held;
            if (delta < 0) {
                sells.add(new TradeAction(position.ticker, TradeSide.SELL, -delta, position.price));
            } else if (delta > 0) {
                buys.add(new TradeAction(position.ticker, TradeSide.BUY, delta, position.price));
            }
        }
        return new RebalancePlan(sells, buys);
    }

    /**
     * Step 2: chooses which underweight positions, sorted by gap per price, get one more share.
     * One more share of a position short of its target by {@code gap} (less than a share) lowers
     * the drift by twice the gap and costs one share price, so with every whole share from step 1
     * kept, the least drift comes from the affordable set with the largest total gap: a 0/1
     * knapsack. Largest gap first is not enough, since one expensive share can block two cheaper
     * ones that close more. Branch and bound finds the set exactly, unless it needs more than
     * {@code searchLimit} steps or there are more than {@value #MAX_SEARCH_CANDIDATES} candidates;
     * then it is the best set found so far (at worst the positions by gap per price), topped up
     * with whatever is still affordable.
     */
    private Set<Position> chooseExtraShares(List<Position> byGapPerPrice, BigDecimal budget) {
        Set<Position> chosen = new LinkedHashSet<>(); // Position keeps identity equality
        if (byGapPerPrice.size() <= MAX_SEARCH_CANDIDATES) {
            GapSearch search = new GapSearch(byGapPerPrice, searchLimit);
            search.explore(0, budget, BigDecimal.ZERO);
            chosen.addAll(search.best);
        }
        BigDecimal left = budget;
        for (Position position : chosen) {
            left = left.subtract(position.price);
        }
        for (Position position : byGapPerPrice) {
            if (!chosen.contains(position) && position.price.compareTo(left) <= 0) {
                chosen.add(position);
                left = left.subtract(position.price);
            }
        }
        return chosen;
    }

    /**
     * Depth-first search over the candidates, sorted by gap per price, trying each with and then
     * without its extra share. The first complete choice it reaches is the greedy one.
     */
    private static final class GapSearch {

        private final List<Position> candidates;
        private final int limit;
        private final Deque<Position> path = new ArrayDeque<>();
        private List<Position> best = List.of();
        private BigDecimal bestGap = BigDecimal.ZERO;
        private int steps;

        private GapSearch(List<Position> candidates, int limit) {
            this.candidates = candidates;
            this.limit = limit;
        }

        private void explore(int next, BigDecimal budget, BigDecimal closedGap) {
            if (closedGap.compareTo(bestGap) > 0) {
                bestGap = closedGap;
                best = List.copyOf(path);
            }
            if (next == candidates.size() || ++steps > limit
                    || upperBound(next, budget, closedGap).compareTo(bestGap) <= 0) {
                return;
            }
            Position candidate = candidates.get(next);
            if (candidate.price.compareTo(budget) <= 0) {
                path.push(candidate);
                explore(next + 1, budget.subtract(candidate.price), closedGap.add(candidate.gap));
                path.pop();
            }
            explore(next + 1, budget, closedGap);
        }

        /**
         * The most gap the candidates from {@code next} on could still close (the fractional
         * knapsack bound): those that fit, in gap per price order, plus the affordable fraction of
         * the first one that doesn't, rounded up.
         */
        private BigDecimal upperBound(int next, BigDecimal budget, BigDecimal closedGap) {
            BigDecimal bound = closedGap;
            BigDecimal left = budget;
            for (int i = next; i < candidates.size(); i++) {
                Position candidate = candidates.get(i);
                if (candidate.price.compareTo(left) > 0) {
                    return bound.add(candidate.gap.multiply(left).divide(candidate.price, BOUND_PRECISION));
                }
                bound = bound.add(candidate.gap);
                left = left.subtract(candidate.price);
            }
            return bound;
        }
    }

    /** Working state for one ticker while a plan is computed. */
    private static final class Position {

        private final String ticker;
        private final BigDecimal price;
        private final long held;
        private final BigDecimal percentage;
        /** Shares to hold once the plan is applied. */
        private long wanted;
        /** Target value minus the value of {@code wanted} shares, as of step 1. */
        private BigDecimal gap;

        private Position(String ticker, BigDecimal price, long held, BigDecimal percentage) {
            this.ticker = ticker;
            this.price = price;
            this.held = held;
            this.percentage = percentage;
        }

        private BigDecimal valueOf(long shares) {
            return price.multiply(BigDecimal.valueOf(shares));
        }
    }
}
