package org.example.portfolio.infra;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.spi.PortfolioRepository;

/** Map-backed repository; one portfolio per account id. Data is lost when the JVM exits. */
public class InMemoryPortfolioRepository implements PortfolioRepository {

    private final Map<String, Portfolio> portfolios = new ConcurrentHashMap<>();

    @Override
    public Optional<Portfolio> findByAccountId(String accountId) {
        return Optional.ofNullable(portfolios.get(accountId));
    }

    @Override
    public void save(Portfolio portfolio) {
        portfolios.put(portfolio.getAccountId(), portfolio);
    }
}
