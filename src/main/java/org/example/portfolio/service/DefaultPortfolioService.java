package org.example.portfolio.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.example.portfolio.api.PortfolioService;
import org.example.portfolio.domain.AllocationReport;
import org.example.portfolio.domain.MarketPrices;
import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.RebalanceStrategy;
import org.example.portfolio.domain.Stock;
import org.example.portfolio.domain.TargetAllocation;
import org.example.portfolio.exception.AccountNotFoundException;
import org.example.portfolio.exception.ConcurrentUpdateException;
import org.example.portfolio.exception.PortfolioAlreadyExistsException;
import org.example.portfolio.exception.PortfolioException;
import org.example.portfolio.exception.PortfolioNotFoundException;
import org.example.portfolio.spi.AccountRepository;
import org.example.portfolio.spi.PortfolioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the use cases: validates the account, loads the portfolio, delegates to
 * the domain and persists the result. All collaborators are injected through interfaces.
 *
 * <p>Thread-safe without locks. Portfolios are immutable, so a change builds a new portfolio
 * from the stored one and commits it with {@link PortfolioRepository#replace} (compare-and-set);
 * if another thread committed first, the change is re-run on the newer state. Every operation
 * therefore reads, and commits, one consistent state of the account. Prices are read once per
 * operation, before any retry, so a retry is pure computation and never waits on market data;
 * after {@value #MAX_COMMIT_ATTEMPTS} lost races the operation fails with
 * {@link ConcurrentUpdateException}.
 */
public class DefaultPortfolioService implements PortfolioService {

    /** Commits to try before giving up; only reached under pathological contention. */
    static final int MAX_COMMIT_ATTEMPTS = 1_000;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPortfolioService.class);

    private final PortfolioRepository repository;
    private final AccountRepository accounts;
    private final MarketDataProvider marketData;
    private final RebalanceStrategy rebalanceStrategy;

    public DefaultPortfolioService(PortfolioRepository repository,
                                   AccountRepository accounts,
                                   MarketDataProvider marketData,
                                   RebalanceStrategy rebalanceStrategy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.marketData = Objects.requireNonNull(marketData, "marketData");
        this.rebalanceStrategy = Objects.requireNonNull(rebalanceStrategy, "rebalanceStrategy");
    }

    @Override
    public PortfolioSnapshot createPortfolio(String accountId) {
        return logged(accountId, "create", () -> {
            String id = Portfolio.normalizeAccountId(accountId);
            if (!accounts.exists(id)) {
                throw new AccountNotFoundException(id);
            }
            Portfolio portfolio = new Portfolio(id);
            if (!repository.saveIfAbsent(portfolio)) {
                throw new PortfolioAlreadyExistsException(id);
            }
            LOG.info("Created portfolio for account {}", id);
            return portfolio.snapshot();
        });
    }

    @Override
    public PortfolioSnapshot getPortfolio(String accountId) {
        return logged(accountId, "show", () -> load(accountId).snapshot());
    }

    @Override
    public PortfolioSnapshot addStock(String accountId, String ticker, long quantity, BigDecimal price) {
        return logged(accountId, "add", () -> {
            Portfolio updated = update(accountId, p -> p.addStock(ticker, quantity, price));
            Stock held = updated.findStock(ticker).orElseThrow();
            LOG.info("Account {}: bought {} {} @ {} -> holding {} @ avg {}",
                    updated.getAccountId(), quantity, held.ticker(), price, held.quantity(), held.averagePurchasePrice());
            return updated.snapshot();
        });
    }

    @Override
    public PortfolioSnapshot sellStock(String accountId, String ticker, long quantity) {
        return logged(accountId, "sell", () -> {
            Portfolio updated = update(accountId, p -> p.sellStock(ticker, quantity));
            long left = updated.findStock(ticker).map(Stock::quantity).orElse(0L);
            LOG.info("Account {}: sold {} {} ({} left)",
                    updated.getAccountId(), quantity, Stock.normalizeTicker(ticker), left);
            return updated.snapshot();
        });
    }

    @Override
    public PortfolioSnapshot setTargetAllocation(String accountId, Map<String, BigDecimal> percentagesByTicker) {
        return logged(accountId, "target", () -> {
            TargetAllocation target = TargetAllocation.of(percentagesByTicker);
            Portfolio updated = update(accountId, p -> p.withTargetAllocation(target));
            LOG.info("Account {}: target allocation set to {}", updated.getAccountId(), target);
            return updated.snapshot();
        });
    }

    @Override
    public AllocationReport getCurrentAllocation(String accountId) {
        return logged(accountId, "allocation", () -> load(accountId).getCurrentAllocation(currentPrices()));
    }

    @Override
    public RebalancePlan rebalance(String accountId) {
        return logged(accountId, "rebalance", () -> {
            Portfolio portfolio = load(accountId);
            RebalancePlan plan = portfolio.rebalance(rebalanceStrategy, currentPrices());
            LOG.info("Account {}: rebalance plan has {} sell(s) and {} buy(s)",
                    portfolio.getAccountId(), plan.sells().size(), plan.buys().size());
            return plan;
        });
    }

    @Override
    public RebalancePlan rebalanceAndApply(String accountId) {
        return logged(accountId, "rebalance-and-apply", () -> {
            MarketPrices prices = currentPrices();
            Outcome<RebalancePlan> applied = commit(accountId, current -> {
                RebalancePlan plan = current.rebalance(rebalanceStrategy, prices);
                return new Outcome<>(current.applyRebalance(plan), plan);
            });
            RebalancePlan plan = applied.result();
            if (plan.isEmpty()) {
                LOG.info("Account {}: already balanced, nothing to apply", applied.portfolio().getAccountId());
            } else {
                LOG.info("Account {}: rebalance applied ({} sell(s), {} buy(s)), cash now {}",
                        applied.portfolio().getAccountId(), plan.sells().size(), plan.buys().size(),
                        applied.portfolio().getCash());
            }
            return plan;
        });
    }

    /** Applies {@code change} to the latest state of the account's portfolio and commits it. */
    private Portfolio update(String accountId, UnaryOperator<Portfolio> change) {
        return commit(accountId, current -> new Outcome<Void>(change.apply(current), null)).portfolio();
    }

    /**
     * Optimistic concurrency: builds the new state from the stored one and commits it only if no
     * other thread committed in between; otherwise re-reads and runs {@code change} again, so
     * {@code change} must not have side effects (filling the operation's price cache is fine).
     *
     * @throws ConcurrentUpdateException after {@value #MAX_COMMIT_ATTEMPTS} lost races
     */
    private <T> Outcome<T> commit(String accountId, Function<Portfolio, Outcome<T>> change) {
        for (int attempt = 1; ; attempt++) {
            Portfolio current = load(accountId);
            Outcome<T> outcome = change.apply(current);
            if (outcome.portfolio() == current || repository.replace(current, outcome.portfolio())) {
                return outcome;
            }
            if (attempt == MAX_COMMIT_ATTEMPTS) {
                throw new ConcurrentUpdateException(current.getAccountId(), attempt);
            }
            LOG.debug("Account {}: concurrent update detected, retrying", current.getAccountId());
        }
    }

    private Portfolio load(String accountId) {
        String id = Portfolio.normalizeAccountId(accountId);
        return repository.findByAccountId(id).orElseThrow(() -> new PortfolioNotFoundException(id));
    }

    /** Prices for one operation, retries included: each ticker is fetched from the market data at most once. */
    private MarketPrices currentPrices() {
        return MarketPrices.from(marketData::getPrice);
    }

    /**
     * Runs an operation and records a failure once, at the boundary, before rethrowing it:
     * rejected business rules at WARN, malformed input at INFO, anything else at ERROR with
     * its stack trace.
     */
    private <T> T logged(String accountId, String operation, Supplier<T> body) {
        try {
            return body.get();
        } catch (PortfolioException e) {
            LOG.warn("Operation '{}' rejected for account {}: {}", operation, accountId, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            LOG.info("Operation '{}' rejected for account {}, invalid input: {}", operation, accountId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            LOG.error("Operation '{}' failed unexpectedly for account {}", operation, accountId, e);
            throw e;
        }
    }

    /** A portfolio state to commit and what the operation reports about it. */
    private record Outcome<T>(Portfolio portfolio, T result) {
    }
}
