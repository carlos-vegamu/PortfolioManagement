package org.example.portfolio.infra;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.spi.PortfolioRepository;

/**
 * Map-backed repository; one portfolio per account id. Data is lost when the JVM exits.
 * Each write is a single atomic {@link ConcurrentHashMap} operation.
 */
public class InMemoryPortfolioRepository implements PortfolioRepository {

    private final ConcurrentMap<String, Portfolio> portfolios = new ConcurrentHashMap<>();

    @Override
    public Optional<Portfolio> findByAccountId(String accountId) {
        return Optional.ofNullable(portfolios.get(accountId));
    }

    @Override
    public boolean saveIfAbsent(Portfolio portfolio) {
        return portfolios.putIfAbsent(portfolio.getAccountId(), portfolio) == null;
    }

    @Override
    public boolean replace(Portfolio expected, Portfolio updated) {
        if (!expected.getAccountId().equals(updated.getAccountId())) {
            throw new IllegalArgumentException("Cannot replace the portfolio of account " + expected.getAccountId()
                    + " with one of account " + updated.getAccountId());
        }
        // Portfolio equality is identity, so this only succeeds if nobody stored a newer one
        return portfolios.replace(expected.getAccountId(), expected, updated);
    }
}
