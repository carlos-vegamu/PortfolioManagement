package org.example.portfolio.api;

import java.math.BigDecimal;
import java.util.Map;

import org.example.portfolio.domain.AllocationReport;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.exception.AccountNotFoundException;
import org.example.portfolio.exception.ConcurrentUpdateException;
import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;
import org.example.portfolio.exception.PortfolioAlreadyExistsException;
import org.example.portfolio.exception.PortfolioNotFoundException;
import org.example.portfolio.exception.PriceUnavailableException;

/**
 * Contract the rest of the application uses to work with portfolios. Every method is
 * keyed by account id; an account owns at most one portfolio.
 *
 * <p>Implementations are safe to call from several threads. Each operation works on one
 * consistent state of the portfolio, and changes are atomic: concurrent changes to the same
 * account never interleave and are never lost. A change that keeps losing to others under
 * extreme contention fails with {@link ConcurrentUpdateException} and can simply be retried.
 */
public interface PortfolioService {

    /**
     * @throws AccountNotFoundException        if the account does not exist
     * @throws PortfolioAlreadyExistsException if the account already has a portfolio
     */
    PortfolioSnapshot createPortfolio(String accountId);

    /** @throws PortfolioNotFoundException if the account has no portfolio */
    PortfolioSnapshot getPortfolio(String accountId);

    /**
     * Records a purchase. Buying a ticker that is already held updates its average purchase price.
     *
     * @throws PortfolioNotFoundException if the account has no portfolio
     * @throws IllegalArgumentException   for a malformed ticker or a non-positive quantity or price
     */
    PortfolioSnapshot addStock(String accountId, String ticker, long quantity, BigDecimal price);

    /**
     * Records a sale.
     *
     * @throws PortfolioNotFoundException     if the account has no portfolio
     * @throws InsufficientQuantityException  if fewer shares are held than requested
     */
    PortfolioSnapshot sellStock(String accountId, String ticker, long quantity);

    /**
     * Replaces the target allocation (percentages by ticker, summing to 100).
     *
     * @throws PortfolioNotFoundException  if the account has no portfolio
     * @throws InvalidAllocationException  if the percentages are invalid
     */
    PortfolioSnapshot setTargetAllocation(String accountId, Map<String, BigDecimal> percentagesByTicker);

    /**
     * Current distribution of the portfolio's value over its stocks and cash, in percent, next to
     * the target allocation.
     *
     * @throws PortfolioNotFoundException   if the account has no portfolio
     * @throws PriceUnavailableException    if a held ticker has no market price
     */
    AllocationReport getCurrentAllocation(String accountId);

    /**
     * Computes, without executing it, the orders needed to reach the target allocation.
     *
     * @throws PortfolioNotFoundException  if the account has no portfolio
     * @throws InvalidAllocationException  if no target allocation has been defined
     * @throws PriceUnavailableException   if a needed ticker has no market price
     */
    RebalancePlan rebalance(String accountId);

    /**
     * Same as {@link #rebalance(String)} but also executes the plan, atomically: the plan is
     * computed from and applied to the same state. Sale proceeds not spent on whole shares stay
     * in the portfolio as cash.
     */
    RebalancePlan rebalanceAndApply(String accountId);
}
