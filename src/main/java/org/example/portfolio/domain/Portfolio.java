package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregate holding the stocks owned by one account and the allocation it is aiming for.
 *
 * <p>The class only guards its own invariants (one position per ticker, positive
 * quantities, valid target). Prices come from an injected {@link MarketDataProvider}
 * and the buy/sell decision from an injected {@link RebalanceStrategy}, so both can be
 * replaced by test doubles. It is not thread-safe.
 */
public class Portfolio {

    private static final Logger LOG = LoggerFactory.getLogger(Portfolio.class);
    private static final int PERCENT_SCALE = 2;

    private final String accountId;
    private final RebalanceStrategy rebalanceStrategy;
    private final Map<String, Stock> stocks = new TreeMap<>();
    private TargetAllocation targetAllocation;

    public Portfolio(String accountId, RebalanceStrategy rebalanceStrategy) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account id must not be blank");
        }
        this.accountId = accountId.trim();
        this.rebalanceStrategy = Objects.requireNonNull(rebalanceStrategy, "rebalanceStrategy");
    }

    public String getAccountId() {
        return accountId;
    }

    /** Current positions, sorted by ticker. The returned set is unmodifiable. */
    public Set<Stock> getStocks() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(stocks.values()));
    }

    public Optional<Stock> findStock(String ticker) {
        return Optional.ofNullable(stocks.get(Stock.normalizeTicker(ticker)));
    }

    public Optional<TargetAllocation> getTargetAllocation() {
        return Optional.ofNullable(targetAllocation);
    }

    /**
     * Buys shares. If the ticker is already held the position grows and its average
     * purchase price becomes the weighted average of old and new purchases.
     */
    public void addStock(String ticker, long quantity, BigDecimal price) {
        String symbol = Stock.normalizeTicker(ticker);
        Stock existing = stocks.get(symbol);
        Stock updated = existing == null
                ? new Stock(symbol, quantity, price)
                : existing.increaseBy(quantity, price);
        stocks.put(symbol, updated);
        LOG.info("Account {}: bought {} {} @ {} -> holding {} @ avg {}",
                accountId, quantity, symbol, price, updated.quantity(), updated.averagePurchasePrice());
    }

    /**
     * Sells shares; the position disappears when fully sold.
     *
     * @throws InsufficientQuantityException if fewer shares are held than requested
     */
    public void sellStock(String ticker, long quantity) {
        String symbol = Stock.normalizeTicker(ticker);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        Stock existing = stocks.get(symbol);
        long held = existing == null ? 0 : existing.quantity();
        if (quantity > held) {
            LOG.warn("Account {}: rejected sale of {} {} (held {})", accountId, quantity, symbol, held);
            throw new InsufficientQuantityException(symbol, quantity, held);
        }
        if (quantity == held) {
            stocks.remove(symbol);
        } else {
            stocks.put(symbol, existing.decreaseBy(quantity));
        }
        LOG.info("Account {}: sold {} {} ({} left)", accountId, quantity, symbol, held - quantity);
    }

    /** Defines the distribution this portfolio is aiming for, replacing any previous one. */
    public void setTargetAllocation(TargetAllocation allocation) {
        this.targetAllocation = Objects.requireNonNull(allocation, "allocation");
        LOG.info("Account {}: target allocation set to {}", accountId, allocation);
    }

    /** Market value of all positions. */
    public BigDecimal getTotalValue(MarketDataProvider prices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Stock stock : stocks.values()) {
            total = total.add(marketValue(stock, prices));
        }
        return total;
    }

    /**
     * Share of the total market value held in each stock, as percentages (scale 2),
     * sorted by ticker. Empty when the portfolio is empty.
     */
    public Map<String, BigDecimal> getCurrentAllocation(MarketDataProvider prices) {
        Map<String, BigDecimal> allocation = new TreeMap<>();
        BigDecimal total = getTotalValue(prices);
        if (total.signum() == 0) {
            return allocation;
        }
        for (Stock stock : stocks.values()) {
            BigDecimal pct = marketValue(stock, prices)
                    .multiply(TargetAllocation.HUNDRED)
                    .divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
            allocation.put(stock.ticker(), pct);
        }
        return allocation;
    }

    /**
     * Works out which stocks to sell and which to buy to match the target allocation.
     * The portfolio is not modified; use {@link #applyRebalance(RebalancePlan)} to execute the plan.
     *
     * @throws InvalidAllocationException if no target allocation has been defined
     */
    public RebalancePlan rebalance(MarketDataProvider prices) {
        if (targetAllocation == null) {
            LOG.warn("Account {}: rebalance requested without a target allocation", accountId);
            throw new InvalidAllocationException("Define a target allocation before rebalancing");
        }
        RebalancePlan plan = rebalanceStrategy.plan(getStocks(), targetAllocation, prices);
        LOG.info("Account {}: rebalance plan has {} sell(s) and {} buy(s)",
                accountId, plan.sells().size(), plan.buys().size());
        return plan;
    }

    /**
     * Executes a plan: sells first, then buys. The plan is validated up front so a
     * failing sale cannot leave the portfolio half-rebalanced.
     */
    public void applyRebalance(RebalancePlan plan) {
        for (TradeAction sell : plan.sells()) {
            long held = findStock(sell.ticker()).map(Stock::quantity).orElse(0L);
            if (sell.quantity() > held) {
                throw new InsufficientQuantityException(sell.ticker(), sell.quantity(), held);
            }
        }
        plan.sells().forEach(a -> sellStock(a.ticker(), a.quantity()));
        plan.buys().forEach(a -> addStock(a.ticker(), a.quantity(), a.price()));
        LOG.info("Account {}: rebalance applied ({} order(s))", accountId, plan.actions().size());
    }

    /** Immutable copy of the current state. */
    public PortfolioSnapshot snapshot() {
        Map<String, BigDecimal> target = targetAllocation == null ? Map.of() : targetAllocation.asMap();
        return new PortfolioSnapshot(accountId, getStocks(), target);
    }

    private static BigDecimal marketValue(Stock stock, MarketDataProvider prices) {
        return prices.getPrice(stock.ticker()).multiply(BigDecimal.valueOf(stock.quantity()));
    }
}
