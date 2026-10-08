package org.example.portfolio.api;

import java.math.BigDecimal;
import java.util.Map;

import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.exception.AccountNotFoundException;
import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;
import org.example.portfolio.exception.PortfolioAlreadyExistsException;
import org.example.portfolio.exception.PortfolioNotFoundException;
import org.example.portfolio.exception.PriceUnavailableException;

/**
 * Contract the rest of the application uses to work with portfolios. Every method is
 * keyed by account id; an account owns at most one portfolio.
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
     * Current distribution of market value by ticker, in percent.
     *
     * @throws PortfolioNotFoundException   if the account has no portfolio
     * @throws PriceUnavailableException    if a held ticker has no market price
     */
    Map<String, BigDecimal> getCurrentAllocation(String accountId);

    /**
     * Computes, without executing it, the orders needed to reach the target allocation.
     *
     * @throws PortfolioNotFoundException  if the account has no portfolio
     * @throws InvalidAllocationException  if no target allocation has been defined
     * @throws PriceUnavailableException   if a needed ticker has no market price
     */
    RebalancePlan rebalance(String accountId);

    /** Same as {@link #rebalance(String)} but also updates the holdings as if the orders were executed. */
    RebalancePlan rebalanceAndApply(String accountId);
}
