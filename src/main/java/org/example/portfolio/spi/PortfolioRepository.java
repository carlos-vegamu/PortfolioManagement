package org.example.portfolio.spi;

import java.util.Optional;

import org.example.portfolio.domain.Portfolio;

/** Persistence port for portfolios, keyed by account id. */
public interface PortfolioRepository {

    Optional<Portfolio> findByAccountId(String accountId);

    /** Inserts or updates the portfolio of {@code portfolio.getAccountId()}. */
    void save(Portfolio portfolio);
}
