package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import org.example.portfolio.exception.InsufficientCashException;
import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;

/**
 * Aggregate holding the stocks owned by one account, its uninvested cash and the allocation
 * it is aiming for.
 *
 * <p>Immutable, so it can be shared between threads: every change returns a new portfolio and
 * leaves this one untouched, which also means a change that fails half-way has no effect.
 * The class only guards its own invariants (one position per ticker, positive quantities,
 * non-negative cash, valid target); prices and the buy/sell policy are passed in by the caller.
 *
 * <p>Equality is identity on purpose, and must stay so: repositories rely on it to detect
 * concurrent updates (see {@code PortfolioRepository#replace}). An {@code equals} by account
 * id would make every compare-and-set succeed and silently bring lost updates back.
 */
public final class Portfolio {

    private static final int PERCENT_SCALE = 2;

    private final String accountId;
    private final SortedMap<String, Stock> stocks;
    private final BigDecimal cash;
    private final TargetAllocation targetAllocation;

    /** An empty portfolio, with no cash and no target allocation. */
    public Portfolio(String accountId) {
        this(normalizeAccountId(accountId), Collections.emptySortedMap(), BigDecimal.ZERO, null);
    }

    /** @param stocks an unmodifiable map nobody else holds a modifiable reference to */
    private Portfolio(String accountId, SortedMap<String, Stock> stocks, BigDecimal cash,
                      TargetAllocation targetAllocation) {
        this.accountId = accountId;
        this.stocks = stocks;
        this.cash = cash;
        this.targetAllocation = targetAllocation;
    }

    /** Trims an account id, rejecting a blank one. */
    public static String normalizeAccountId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Account id must not be blank");
        }
        return raw.trim();
    }

    public String getAccountId() {
        return accountId;
    }

    /** Current positions by ticker, sorted. The returned map is unmodifiable. */
    public SortedMap<String, Stock> getStocks() {
        return stocks;
    }

    public Optional<Stock> findStock(String ticker) {
        return Optional.ofNullable(stocks.get(Stock.normalizeTicker(ticker)));
    }

    /** Uninvested cash: rebalance proceeds that were not spent on whole shares. Never negative. */
    public BigDecimal getCash() {
        return cash;
    }

    public Optional<TargetAllocation> getTargetAllocation() {
        return Optional.ofNullable(targetAllocation);
    }

    /**
     * Records a purchase paid for outside the portfolio, so the cash is unchanged. If the ticker
     * is already held the position grows and its average purchase price becomes the weighted
     * average of old and new purchases.
     *
     * @return the portfolio after the purchase
     */
    public Portfolio addStock(String ticker, long quantity, BigDecimal price) {
        String symbol = Stock.normalizeTicker(ticker);
        Stock updated = bought(stocks.get(symbol), symbol, quantity, price);
        SortedMap<String, Stock> next = new TreeMap<>(stocks);
        next.put(symbol, updated);
        return withStocks(next, cash);
    }

    /**
     * Records a sale whose proceeds leave the portfolio, so the cash is unchanged. The position
     * disappears when fully sold.
     *
     * @return the portfolio after the sale
     * @throws InsufficientQuantityException if fewer shares are held than requested
     */
    public Portfolio sellStock(String ticker, long quantity) {
        String symbol = Stock.normalizeTicker(ticker);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        requireHeld(symbol, quantity);
        SortedMap<String, Stock> next = new TreeMap<>(stocks);
        sold(next, symbol, quantity);
        return withStocks(next, cash);
    }

    /** @return the portfolio aiming for {@code allocation} instead of any previous target */
    public Portfolio withTargetAllocation(TargetAllocation allocation) {
        return new Portfolio(accountId, stocks, cash, Objects.requireNonNull(allocation, "allocation"));
    }

    /** Market value of all positions plus the cash. */
    public BigDecimal getTotalValue(MarketPrices prices) {
        BigDecimal total = cash;
        for (Stock stock : stocks.values()) {
            total = total.add(marketValue(stock, prices));
        }
        return total;
    }

    /**
     * How the total value (positions plus cash) is distributed, in percent (scale 2), next to
     * the target. Each position is valued once. Empty when the portfolio holds nothing.
     */
    public AllocationReport getCurrentAllocation(MarketPrices prices) {
        SortedMap<String, BigDecimal> current = new TreeMap<>();
        BigDecimal total = cash;
        for (Stock stock : stocks.values()) {
            BigDecimal value = marketValue(stock, prices);
            current.put(stock.ticker(), value);
            total = total.add(value);
        }
        BigDecimal cashPercentage = BigDecimal.ZERO.setScale(PERCENT_SCALE);
        if (total.signum() != 0) {
            BigDecimal denominator = total;
            current.replaceAll((ticker, value) -> percentOf(value, denominator));
            cashPercentage = percentOf(cash, denominator);
        }
        return new AllocationReport(current, cashPercentage, getTargetAllocation());
    }

    /**
     * Works out, with the given policy, which stocks to sell and which to buy to match the
     * target allocation. The portfolio is not modified; use {@link #applyRebalance(RebalancePlan)}
     * to execute the plan.
     *
     * @throws InvalidAllocationException if no target allocation has been defined
     */
    public RebalancePlan rebalance(RebalanceStrategy strategy, MarketPrices prices) {
        Objects.requireNonNull(strategy, "strategy");
        if (targetAllocation == null) {
            throw new InvalidAllocationException("Define a target allocation before rebalancing");
        }
        return strategy.plan(stocks, cash, targetAllocation, prices);
    }

    /**
     * Executes a plan: sells first, crediting their proceeds to the cash, then buys, paid from
     * the cash. The whole plan is checked before anything changes, so it is applied completely
     * or not at all.
     *
     * <p>The plan's prices are taken as given, so only apply plans computed from this portfolio
     * at current prices, as {@code PortfolioService.rebalanceAndApply} does; a hand-made plan
     * selling at an invented price would credit invented cash.
     *
     * @return the portfolio after the plan; this same portfolio when the plan is empty
     * @throws InsufficientQuantityException if the plan sells more shares of a ticker, in total, than are held
     * @throws InsufficientCashException     if the buys cost more than the cash plus the sale proceeds
     */
    public Portfolio applyRebalance(RebalancePlan plan) {
        if (plan.isEmpty()) {
            return this;
        }
        Map<String, Long> soldByTicker = new LinkedHashMap<>();
        for (TradeAction sell : plan.sells()) {
            soldByTicker.merge(sell.ticker(), sell.quantity(), Math::addExact);
        }
        soldByTicker.forEach(this::requireHeld);
        BigDecimal available = cash.add(plan.proceeds());
        BigDecimal cost = plan.cost();
        if (cost.compareTo(available) > 0) {
            throw new InsufficientCashException(cost, available);
        }

        SortedMap<String, Stock> next = new TreeMap<>(stocks);
        soldByTicker.forEach((ticker, quantity) -> sold(next, ticker, quantity));
        for (TradeAction buy : plan.buys()) {
            next.put(buy.ticker(), bought(next.get(buy.ticker()), buy.ticker(), buy.quantity(), buy.price()));
        }
        return withStocks(next, available.subtract(cost));
    }

    /** Immutable copy of the current state. */
    public PortfolioSnapshot snapshot() {
        return new PortfolioSnapshot(accountId, stocks, cash, getTargetAllocation());
    }

    private Portfolio withStocks(SortedMap<String, Stock> next, BigDecimal newCash) {
        return new Portfolio(accountId, Collections.unmodifiableSortedMap(next), newCash, targetAllocation);
    }

    private void requireHeld(String ticker, long quantity) {
        Stock stock = stocks.get(ticker);
        long held = stock == null ? 0 : stock.quantity();
        if (quantity > held) {
            throw new InsufficientQuantityException(ticker, quantity, held);
        }
    }

    private static Stock bought(Stock held, String ticker, long quantity, BigDecimal price) {
        return held == null ? new Stock(ticker, quantity, price) : held.increaseBy(quantity, price);
    }

    /** Removes {@code quantity} shares, known to be held, from {@code stocks}. */
    private static void sold(SortedMap<String, Stock> stocks, String ticker, long quantity) {
        Stock held = stocks.get(ticker);
        if (quantity == held.quantity()) {
            stocks.remove(ticker);
        } else {
            stocks.put(ticker, held.decreaseBy(quantity));
        }
    }

    private static BigDecimal marketValue(Stock stock, MarketPrices prices) {
        return prices.priceOf(stock.ticker()).multiply(BigDecimal.valueOf(stock.quantity()));
    }

    private static BigDecimal percentOf(BigDecimal part, BigDecimal total) {
        return part.multiply(TargetAllocation.HUNDRED).divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
