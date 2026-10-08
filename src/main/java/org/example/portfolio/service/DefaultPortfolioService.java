package org.example.portfolio.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.example.portfolio.api.PortfolioService;
import org.example.portfolio.domain.MarketDataProvider;
import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.RebalanceStrategy;
import org.example.portfolio.domain.TargetAllocation;
import org.example.portfolio.exception.AccountNotFoundException;
import org.example.portfolio.exception.PortfolioAlreadyExistsException;
import org.example.portfolio.exception.PortfolioException;
import org.example.portfolio.exception.PortfolioNotFoundException;
import org.example.portfolio.spi.AccountDirectory;
import org.example.portfolio.spi.PortfolioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the use cases: validates the account, loads the portfolio, delegates to
 * the domain and persists the result. All collaborators are injected through interfaces.
 */
public class DefaultPortfolioService implements PortfolioService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPortfolioService.class);

    private final PortfolioRepository repository;
    private final AccountDirectory accounts;
    private final MarketDataProvider marketData;
    private final RebalanceStrategy rebalanceStrategy;

    public DefaultPortfolioService(PortfolioRepository repository,
                                   AccountDirectory accounts,
                                   MarketDataProvider marketData,
                                   RebalanceStrategy rebalanceStrategy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.marketData = Objects.requireNonNull(marketData, "marketData");
        this.rebalanceStrategy = Objects.requireNonNull(rebalanceStrategy, "rebalanceStrategy");
    }

    @Override
    public PortfolioSnapshot createPortfolio(String accountId) {
        String id = requireId(accountId);
        try {
            if (!accounts.exists(id)) {
                throw new AccountNotFoundException(id);
            }
            if (repository.findByAccountId(id).isPresent()) {
                throw new PortfolioAlreadyExistsException(id);
            }
            Portfolio portfolio = new Portfolio(id, rebalanceStrategy);
            repository.save(portfolio);
            LOG.info("Created portfolio for account {}", id);
            return portfolio.snapshot();
        } catch (PortfolioException e) {
            LOG.error("Could not create portfolio for account {}: {}", id, e.getMessage());
            throw e;
        }
    }

    @Override
    public PortfolioSnapshot getPortfolio(String accountId) {
        return load(accountId).snapshot();
    }

    @Override
    public PortfolioSnapshot addStock(String accountId, String ticker, long quantity, BigDecimal price) {
        return mutate(accountId, p -> p.addStock(ticker, quantity, price));
    }

    @Override
    public PortfolioSnapshot sellStock(String accountId, String ticker, long quantity) {
        return mutate(accountId, p -> p.sellStock(ticker, quantity));
    }

    @Override
    public PortfolioSnapshot setTargetAllocation(String accountId, Map<String, BigDecimal> percentagesByTicker) {
        return mutate(accountId, p -> p.setTargetAllocation(TargetAllocation.of(percentagesByTicker)));
    }

    @Override
    public Map<String, BigDecimal> getCurrentAllocation(String accountId) {
        return logged(accountId, "allocation", () -> load(accountId).getCurrentAllocation(marketData));
    }

    @Override
    public RebalancePlan rebalance(String accountId) {
        return logged(accountId, "rebalance", () -> load(accountId).rebalance(marketData));
    }

    @Override
    public RebalancePlan rebalanceAndApply(String accountId) {
        return logged(accountId, "rebalance-and-apply", () -> {
            Portfolio portfolio = load(accountId);
            RebalancePlan plan = portfolio.rebalance(marketData);
            portfolio.applyRebalance(plan);
            repository.save(portfolio);
            return plan;
        });
    }

    private PortfolioSnapshot mutate(String accountId, Consumer<Portfolio> change) {
        return logged(accountId, "update", () -> {
            Portfolio portfolio = load(accountId);
            change.accept(portfolio);
            repository.save(portfolio);
            return portfolio.snapshot();
        });
    }

    private Portfolio load(String accountId) {
        String id = requireId(accountId);
        return repository.findByAccountId(id).orElseThrow(() -> new PortfolioNotFoundException(id));
    }

    /** Runs an operation and records failures once, at the boundary, before rethrowing them. */
    private <T> T logged(String accountId, String operation, Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            LOG.error("Operation '{}' failed for account {}: {}", operation, accountId, e.getMessage());
            throw e;
        }
    }

    private static String requireId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account id must not be blank");
        }
        return accountId.trim();
    }
}
