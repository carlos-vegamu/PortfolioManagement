package org.example.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.example.portfolio.domain.MarketDataProvider;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.RebalanceStrategy;
import org.example.portfolio.domain.Stock;
import org.example.portfolio.domain.TargetAllocation;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.domain.TradeSide;

/**
 * Self-financing rebalancing: the current market value of the portfolio is redistributed
 * according to the target percentages, so the plan never needs extra cash.
 *
 * <ul>
 *   <li>Holdings missing from the target are sold completely.</li>
 *   <li>Orders are whole shares: sells are rounded to the nearest share, buys are rounded down.</li>
 *   <li>Total buys are capped by the proceeds of the sells; the most underweight stocks are served first.</li>
 *   <li>An empty portfolio yields an empty plan because there is no money to distribute.</li>
 * </ul>
 */
public class ProportionalRebalanceStrategy implements RebalanceStrategy {

    @Override
    public RebalancePlan plan(Collection<Stock> holdings, TargetAllocation target, MarketDataProvider prices) {
        if (holdings.isEmpty()) {
            return RebalancePlan.empty();
        }

        Map<String, Long> held = new TreeMap<>();
        holdings.forEach(s -> held.put(s.ticker(), s.quantity()));

        Set<String> tickers = new TreeSet<>(held.keySet());
        tickers.addAll(target.tickers());

        Map<String, BigDecimal> priceByTicker = new TreeMap<>();
        tickers.forEach(t -> priceByTicker.put(t, prices.getPrice(t)));

        BigDecimal totalValue = BigDecimal.ZERO;
        for (Map.Entry<String, Long> entry : held.entrySet()) {
            totalValue = totalValue.add(priceByTicker.get(entry.getKey()).multiply(BigDecimal.valueOf(entry.getValue())));
        }

        List<TradeAction> sells = new ArrayList<>();
        List<Deficit> deficits = new ArrayList<>();
        BigDecimal proceeds = BigDecimal.ZERO;
        for (String ticker : tickers) {
            BigDecimal price = priceByTicker.get(ticker);
            BigDecimal currentValue = price.multiply(BigDecimal.valueOf(held.getOrDefault(ticker, 0L)));
            BigDecimal targetValue = totalValue
                    .multiply(target.percentageFor(ticker))
                    .divide(TargetAllocation.HUNDRED);
            BigDecimal gap = targetValue.subtract(currentValue);

            if (gap.signum() < 0) {
                long shares = gap.negate().divide(price, 0, RoundingMode.HALF_UP).longValueExact();
                if (shares > 0) {
                    TradeAction sell = new TradeAction(ticker, TradeSide.SELL, shares, price);
                    sells.add(sell);
                    proceeds = proceeds.add(sell.value());
                }
            } else if (gap.signum() > 0) {
                deficits.add(new Deficit(ticker, price, gap));
            }
        }

        List<TradeAction> actions = new ArrayList<>(sells);
        deficits.sort(Comparator.comparing(Deficit::gap).reversed().thenComparing(Deficit::ticker));
        BigDecimal budget = proceeds;
        for (Deficit deficit : deficits) {
            long wanted = deficit.gap().divide(deficit.price(), 0, RoundingMode.DOWN).longValueExact();
            long affordable = budget.divide(deficit.price(), 0, RoundingMode.DOWN).longValueExact();
            long shares = Math.min(wanted, affordable);
            if (shares > 0) {
                TradeAction buy = new TradeAction(deficit.ticker(), TradeSide.BUY, shares, deficit.price());
                actions.add(buy);
                budget = budget.subtract(buy.value());
            }
        }
        return new RebalancePlan(actions);
    }

    /** A stock that holds less than its target; {@code gap} is the missing market value. */
    private record Deficit(String ticker, BigDecimal price, BigDecimal gap) {
    }
}
