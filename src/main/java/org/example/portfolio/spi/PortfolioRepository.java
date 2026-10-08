package org.example.portfolio.spi;

import java.util.Optional;

import org.example.portfolio.domain.Portfolio;

/**
 * Persistence port for portfolios, keyed by account id. Implementations must be safe to call
 * from several threads, and each write must be atomic.
 */
public interface PortfolioRepository {

    Optional<Portfolio> findByAccountId(String accountId);

    /**
     * Stores a new portfolio unless its account already has one.
     *
     * @return {@code false}, storing nothing, if the account already has a portfolio
     */
    boolean saveIfAbsent(Portfolio portfolio);

    /**
     * Compare-and-set: stores {@code updated} only if {@code expected} is still the stored
     * portfolio of the account.
     *
     * @return {@code false}, storing nothing, if another update was stored since {@code expected} was read
     */
    boolean replace(Portfolio expected, Portfolio updated);
}
