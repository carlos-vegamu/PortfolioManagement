package org.example.portfolio.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
 *       the most it can afford, which leaves the least total drift (see {@code closeLargestGaps}).</li>
 *   <li>Cash still left cancels sells of targeted tickers, so shares are never sold only to sit
 *       in the portfolio as cash. This does not change the drift (the cash would count as drift
 *       instead) but saves trades, and it is why a ticker can stay more than one share over its
 *       target when no underweight ticker is affordable.</li>
 * </ol>
 *
 * <p>Drift is the total distance from the target: the sum, over every ticker and the cash, of
 * the difference between its value and its target value (the cash's target is zero).
 * Buys never cost more than the cash plus the sale proceeds, whatever is not spent stays in
 * the portfolio as cash, and planning again right after applying a plan, at the same prices,
 * gives an empty plan. An empty portfolio without cash gives an empty plan.
 * Runs in O(n log n) for n tickers held or targeted, plus a search bounded by
 * {@code SEARCH_LIMIT} steps of O(n), with one price lookup per ticker.
 */
public class ProportionalRebalanceStrategy implements RebalanceStrategy {

    /** Steps the step-2 search may take; enough to be exact for any realistic portfolio. */
    static final int SEARCH_LIMIT = 10_000;

    /** Most gap closed per unit of cash first, i.e. by gap / price, compared without dividing. */
    private static final Comparator<Position> MOST_GAP_PER_PRICE_FIRST = (a, b) -> {
        int byGapPerPrice = b.gap.multiply(a.price).compareTo(a.gap.multiply(b.price));
        return byGapPerPrice != 0 ? byGapPerPrice : a.ticker.compareTo(b.ticker);
    };

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
        for (Position position : closeLargestGaps(underweight, leftover)) {
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
     * Chooses which underweight positions get one more share. One more share of a position that is
     * short of its target by {@code gap} (less than a share) lowers the drift by twice the gap and
     * costs one share price, so the least drift comes from the affordable set with the largest total
     * gap: a 0/1 knapsack. Largest gap first is not enough, since one expensive share can block two
     * cheaper ones that close more. The set is found by branch and bound; should the search hit
     * {@link #SEARCH_LIMIT}, it is the best set found so far, never worse than taking positions by
     * gap per price, topped up with whatever is still affordable.
     */
    private static List<Position> closeLargestGaps(List<Position> underweight, BigDecimal budget) {
        underweight.sort(MOST_GAP_PER_PRICE_FIRST);
        GapSearch search = new GapSearch(underweight);
        search.explore(0, budget, BigDecimal.ZERO);

        List<Position> chosen = new ArrayList<>(search.best);
        BigDecimal left = budget;
        for (Position position : chosen) {
            left = left.subtract(position.price);
        }
        for (Position position : underweight) {
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
        private final Deque<Position> path = new ArrayDeque<>();
        private List<Position> best = List.of();
        private BigDecimal bestGap = BigDecimal.ZERO;
        private int steps;

        private GapSearch(List<Position> candidates) {
            this.candidates = candidates;
        }

        private void explore(int next, BigDecimal budget, BigDecimal closedGap) {
            if (closedGap.compareTo(bestGap) > 0) {
                bestGap = closedGap;
                best = List.copyOf(path);
            }
            if (next == candidates.size() || ++steps > SEARCH_LIMIT
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
         * The most gap the candidates from {@code next} on could still close: those that fit, in gap
         * per price order, plus the whole gap of the first one that doesn't (the fractional knapsack
         * bound, rounded up).
         */
        private BigDecimal upperBound(int next, BigDecimal budget, BigDecimal closedGap) {
            BigDecimal bound = closedGap;
            BigDecimal left = budget;
            for (int i = next; i < candidates.size(); i++) {
                Position candidate = candidates.get(i);
                bound = bound.add(candidate.gap);
                if (candidate.price.compareTo(left) > 0) {
                    break;
                }
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
