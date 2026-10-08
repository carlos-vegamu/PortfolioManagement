package org.example.portfolio.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.example.portfolio.domain.MarketDataProvider;
import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.RebalanceStrategy;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.domain.TradeSide;
import org.example.portfolio.exception.AccountNotFoundException;
import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;
import org.example.portfolio.exception.PortfolioAlreadyExistsException;
import org.example.portfolio.exception.PortfolioNotFoundException;
import org.example.portfolio.spi.AccountDirectory;
import org.example.portfolio.spi.PortfolioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultPortfolioServiceTest {

    private static final String ACCOUNT = "acc-1";

    @Mock
    private PortfolioRepository repository;
    @Mock
    private AccountDirectory accounts;
    @Mock
    private MarketDataProvider marketData;
    @Mock
    private RebalanceStrategy strategy;

    private DefaultPortfolioService service;

    @BeforeEach
    void setUp() {
        service = new DefaultPortfolioService(repository, accounts, marketData, strategy);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private Portfolio existingPortfolio() {
        Portfolio portfolio = new Portfolio(ACCOUNT, strategy);
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(portfolio));
        return portfolio;
    }

    // ---- create ------------------------------------------------------------

    @Test
    void createPortfolioSavesANewEmptyPortfolio() {
        when(accounts.exists(ACCOUNT)).thenReturn(true);
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        PortfolioSnapshot snapshot = service.createPortfolio("  " + ACCOUNT + " ");

        assertEquals(ACCOUNT, snapshot.accountId());
        assertTrue(snapshot.stocks().isEmpty());
        ArgumentCaptor<Portfolio> saved = ArgumentCaptor.forClass(Portfolio.class);
        verify(repository).save(saved.capture());
        assertEquals(ACCOUNT, saved.getValue().getAccountId());
    }

    @Test
    void createPortfolioFailsForUnknownAccount() {
        when(accounts.exists(ACCOUNT)).thenReturn(false);

        assertThrows(AccountNotFoundException.class, () -> service.createPortfolio(ACCOUNT));

        verify(repository, never()).save(any());
    }

    @Test
    void anAccountCanOnlyHaveOnePortfolio() {
        when(accounts.exists(ACCOUNT)).thenReturn(true);
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(new Portfolio(ACCOUNT, strategy)));

        assertThrows(PortfolioAlreadyExistsException.class, () -> service.createPortfolio(ACCOUNT));

        verify(repository, never()).save(any());
    }

    @Test
    void blankAccountIdIsRejectedEverywhere() {
        assertThrows(IllegalArgumentException.class, () -> service.createPortfolio(" "));
        assertThrows(IllegalArgumentException.class, () -> service.getPortfolio(null));
    }

    // ---- read / update -----------------------------------------------------

    @Test
    void getPortfolioReturnsSnapshot() {
        existingPortfolio().addStock("META", 3, bd("100"));

        assertEquals(1, service.getPortfolio(ACCOUNT).stocks().size());
    }

    @Test
    void getPortfolioFailsWhenAccountHasNone() {
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThrows(PortfolioNotFoundException.class, () -> service.getPortfolio(ACCOUNT));
    }

    @Test
    void addStockUpdatesAndPersists() {
        Portfolio portfolio = existingPortfolio();

        PortfolioSnapshot snapshot = service.addStock(ACCOUNT, "meta", 10, bd("500"));

        assertEquals(1, snapshot.stocks().size());
        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
        verify(repository).save(portfolio);
    }

    @Test
    void addStockFailsWhenAccountHasNoPortfolio() {
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThrows(PortfolioNotFoundException.class, () -> service.addStock(ACCOUNT, "META", 1, bd("1")));

        verify(repository, never()).save(any());
    }

    @Test
    void sellStockUpdatesAndPersists() {
        Portfolio portfolio = existingPortfolio();
        portfolio.addStock("META", 10, bd("500"));

        service.sellStock(ACCOUNT, "META", 4);

        assertEquals(6, portfolio.findStock("META").orElseThrow().quantity());
        verify(repository).save(portfolio);
    }

    @Test
    void sellStockPropagatesBusinessErrorsWithoutSaving() {
        existingPortfolio();

        assertThrows(InsufficientQuantityException.class, () -> service.sellStock(ACCOUNT, "META", 1));

        verify(repository, never()).save(any());
    }

    @Test
    void setTargetAllocationValidatesAndPersists() {
        Portfolio portfolio = existingPortfolio();

        PortfolioSnapshot snapshot = service.setTargetAllocation(ACCOUNT, Map.of("META", bd("40"), "AAPL", bd("60")));

        assertEquals(2, snapshot.targetAllocation().size());
        assertTrue(portfolio.getTargetAllocation().isPresent());
        verify(repository).save(portfolio);
    }

    @Test
    void setTargetAllocationRejectsInvalidPercentages() {
        existingPortfolio();

        assertThrows(InvalidAllocationException.class,
                () -> service.setTargetAllocation(ACCOUNT, Map.of("META", bd("40"), "AAPL", bd("40"))));

        verify(repository, never()).save(any());
    }

    // ---- allocation & rebalance --------------------------------------------

    @Test
    void currentAllocationUsesMarketData() {
        existingPortfolio().addStock("META", 10, bd("1"));
        when(marketData.getPrice("META")).thenReturn(bd("500"));

        Map<String, BigDecimal> allocation = service.getCurrentAllocation(ACCOUNT);

        assertEquals(bd("100.00"), allocation.get("META"));
    }

    @Test
    void rebalanceReturnsPlanWithoutChangingHoldings() {
        Portfolio portfolio = existingPortfolio();
        portfolio.addStock("META", 10, bd("500"));
        service.setTargetAllocation(ACCOUNT, Map.of("AAPL", bd("100")));
        RebalancePlan plan = new RebalancePlan(List.of(new TradeAction("META", TradeSide.SELL, 10, bd("500"))));
        when(strategy.plan(any(), any(), any())).thenReturn(plan);

        assertEquals(plan, service.rebalance(ACCOUNT));

        assertEquals(10, portfolio.findStock("META").orElseThrow().quantity());
    }

    @Test
    void rebalanceWithoutTargetFails() {
        existingPortfolio();

        assertThrows(InvalidAllocationException.class, () -> service.rebalance(ACCOUNT));
    }

    @Test
    void rebalanceAndApplyExecutesPlanAndPersists() {
        Portfolio portfolio = existingPortfolio();
        portfolio.addStock("META", 10, bd("500"));
        portfolio.setTargetAllocation(org.example.portfolio.domain.TargetAllocation.of(Map.of("AAPL", bd("100"))));
        RebalancePlan plan = new RebalancePlan(List.of(
                new TradeAction("META", TradeSide.SELL, 10, bd("500")),
                new TradeAction("AAPL", TradeSide.BUY, 26, bd("190"))));
        when(strategy.plan(any(), any(), any())).thenReturn(plan);

        RebalancePlan result = service.rebalanceAndApply(ACCOUNT);

        assertEquals(plan, result);
        assertTrue(portfolio.findStock("META").isEmpty());
        assertEquals(26, portfolio.findStock("AAPL").orElseThrow().quantity());
        verify(repository).save(portfolio);
    }

    // ---- construction ------------------------------------------------------

    @Test
    void constructorRequiresAllCollaborators() {
        assertThrows(NullPointerException.class, () -> new DefaultPortfolioService(null, accounts, marketData, strategy));
        assertThrows(NullPointerException.class, () -> new DefaultPortfolioService(repository, null, marketData, strategy));
        assertThrows(NullPointerException.class, () -> new DefaultPortfolioService(repository, accounts, null, strategy));
        assertThrows(NullPointerException.class, () -> new DefaultPortfolioService(repository, accounts, marketData, null));
    }
}
