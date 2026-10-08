package org.example.portfolio.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
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
 *   <li>The cash this leaves buys one more share of the most underweight tickers, largest gap
 *       first, while it can afford them.</li>
 *   <li>Cash still left cancels sells of targeted tickers, so shares are never sold only to sit
 *       in the portfolio as cash.</li>
 * </ol>
 *
 * <p>Buys never cost more than the cash plus the sale proceeds, whatever is not spent stays in
 * the portfolio as cash, and planning again right after applying a plan, at the same prices,
 * gives an empty plan. An empty portfolio without cash gives an empty plan.
 * Runs in O(n log n) for n tickers held or targeted, with one price lookup per ticker.
 */
public class ProportionalRebalanceStrategy implements RebalanceStrategy {

    private static final Comparator<Position> LARGEST_GAP_FIRST =
            Comparator.comparing((Position p) -> p.gap).reversed().thenComparing(p -> p.ticker);

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

        // 2. one more share of the most underweight tickers, while the leftover pays for it
        underweight.sort(LARGEST_GAP_FIRST);
        for (Position position : underweight) {
            if (position.price.compareTo(leftover) <= 0) {
                position.wanted++;
                leftover = leftover.subtract(position.price);
            }
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
