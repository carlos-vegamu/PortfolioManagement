package org.example.portfolio.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.example.portfolio.domain.AllocationReport;
import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.RebalanceStrategy;
import org.example.portfolio.domain.TargetAllocation;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.domain.TradeSide;
import org.example.portfolio.exception.AccountNotFoundException;
import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.InvalidAllocationException;
import org.example.portfolio.exception.PortfolioAlreadyExistsException;
import org.example.portfolio.exception.PortfolioNotFoundException;
import org.example.portfolio.spi.AccountRepository;
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
    private AccountRepository accounts;
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

    private Portfolio stored(Portfolio portfolio) {
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(portfolio));
        return portfolio;
    }

    /** Lets the commit over {@code current} succeed. */
    private void commitSucceedsOver(Portfolio current) {
        when(repository.replace(same(current), any())).thenReturn(true);
    }

    private Portfolio committedOver(Portfolio current) {
        ArgumentCaptor<Portfolio> committed = ArgumentCaptor.forClass(Portfolio.class);
        verify(repository).replace(same(current), committed.capture());
        return committed.getValue();
    }

    // ---- create ------------------------------------------------------------

    @Test
    void createPortfolioStoresANewEmptyPortfolio() {
        when(accounts.exists(ACCOUNT)).thenReturn(true);
        when(repository.saveIfAbsent(any())).thenReturn(true);

        PortfolioSnapshot snapshot = service.createPortfolio("  " + ACCOUNT + " ");

        assertEquals(ACCOUNT, snapshot.accountId());
        assertTrue(snapshot.stocks().isEmpty());
        ArgumentCaptor<Portfolio> saved = ArgumentCaptor.forClass(Portfolio.class);
        verify(repository).saveIfAbsent(saved.capture());
        assertEquals(ACCOUNT, saved.getValue().getAccountId());
    }

    @Test
    void createPortfolioFailsForUnknownAccount() {
        when(accounts.exists(ACCOUNT)).thenReturn(false);

        assertThrows(AccountNotFoundException.class, () -> service.createPortfolio(ACCOUNT));

        verify(repository, never()).saveIfAbsent(any());
    }

    @Test
    void anAccountCanOnlyHaveOnePortfolio() {
        when(accounts.exists(ACCOUNT)).thenReturn(true);
        when(repository.saveIfAbsent(any())).thenReturn(false);

        assertThrows(PortfolioAlreadyExistsException.class, () -> service.createPortfolio(ACCOUNT));
    }

    @Test
    void blankAccountIdIsRejectedEverywhere() {
        assertThrows(IllegalArgumentException.class, () -> service.createPortfolio(" "));
        assertThrows(IllegalArgumentException.class, () -> service.getPortfolio(null));
        verifyNoInteractions(repository, accounts);
    }

    // ---- read / update -----------------------------------------------------

    @Test
    void getPortfolioReturnsSnapshot() {
        stored(new Portfolio(ACCOUNT).addStock("META", 3, bd("100")));

        assertEquals(1, service.getPortfolio(ACCOUNT).stocks().size());
    }

    @Test
    void getPortfolioFailsWhenAccountHasNone() {
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThrows(PortfolioNotFoundException.class, () -> service.getPortfolio(ACCOUNT));
    }

    @Test
    void addStockCommitsTheUpdatedPortfolio() {
        Portfolio current = stored(new Portfolio(ACCOUNT));
        commitSucceedsOver(current);

        PortfolioSnapshot snapshot = service.addStock(ACCOUNT, "meta", 10, bd("500"));

        assertEquals(10, snapshot.stocks().get("META").quantity());
        assertEquals(10, committedOver(current).findStock("META").orElseThrow().quantity());
        assertTrue(current.getStocks().isEmpty(), "the stored portfolio itself is never modified");
    }

    @Test
    void addStockIsRetriedOnTheNewerStateWhenAnotherUpdateWinsTheRace() {
        Portfolio first = new Portfolio(ACCOUNT);
        Portfolio concurrent = first.addStock("AAPL", 5, bd("190"));
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(first), Optional.of(concurrent));
        when(repository.replace(same(first), any())).thenReturn(false);
        commitSucceedsOver(concurrent);

        PortfolioSnapshot snapshot = service.addStock(ACCOUNT, "META", 10, bd("500"));

        assertEquals(5, snapshot.stocks().get("AAPL").quantity(), "the concurrent purchase is kept");
        assertEquals(10, snapshot.stocks().get("META").quantity());
        verify(repository, times(2)).findByAccountId(ACCOUNT);
    }

    @Test
    void addStockFailsWhenAccountHasNoPortfolio() {
        when(repository.findByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThrows(PortfolioNotFoundException.class, () -> service.addStock(ACCOUNT, "META", 1, bd("1")));

        verify(repository, never()).replace(any(), any());
    }

    @Test
    void sellStockCommitsTheUpdatedPortfolio() {
        Portfolio current = stored(new Portfolio(ACCOUNT).addStock("META", 10, bd("500")));
        commitSucceedsOver(current);

        service.sellStock(ACCOUNT, "META", 4);

        assertEquals(6, committedOver(current).findStock("META").orElseThrow().quantity());
    }

    @Test
    void sellStockPropagatesBusinessErrorsWithoutCommitting() {
        stored(new Portfolio(ACCOUNT));

        assertThrows(InsufficientQuantityException.class, () -> service.sellStock(ACCOUNT, "META", 1));

        verify(repository, never()).replace(any(), any());
    }

    @Test
    void setTargetAllocationValidatesAndCommits() {
        Portfolio current = stored(new Portfolio(ACCOUNT));
        commitSucceedsOver(current);

        PortfolioSnapshot snapshot = service.setTargetAllocation(ACCOUNT, Map.of("META", bd("40"), "AAPL", bd("60")));

        assertEquals(2, snapshot.targetAllocation().orElseThrow().asMap().size());
        assertTrue(committedOver(current).getTargetAllocation().isPresent());
    }

    @Test
    void setTargetAllocationRejectsInvalidPercentagesBeforeTouchingTheRepository() {
        assertThrows(InvalidAllocationException.class,
                () -> service.setTargetAllocation(ACCOUNT, Map.of("META", bd("40"), "AAPL", bd("40"))));

        verifyNoInteractions(repository);
    }

    // ---- allocation & rebalance --------------------------------------------

    @Test
    void currentAllocationAsksTheMarketDataOncePerTicker() {
        stored(new Portfolio(ACCOUNT).addStock("META", 10, bd("1")));
        when(marketData.getPrice("META")).thenReturn(bd("500"));

        AllocationReport report = service.getCurrentAllocation(ACCOUNT);

        assertEquals(bd("100.00"), report.current().get("META"));
        verify(marketData, times(1)).getPrice("META");
    }

    @Test
    void rebalanceReturnsPlanWithoutCommitting() {
        stored(new Portfolio(ACCOUNT).addStock("META", 10, bd("500"))
                .withTargetAllocation(TargetAllocation.of(Map.of("AAPL", bd("100")))));
        RebalancePlan plan = new RebalancePlan(List.of(new TradeAction("META", TradeSide.SELL, 10, bd("500"))), List.of());
        when(strategy.plan(any(), any(), any(), any())).thenReturn(plan);

        assertEquals(plan, service.rebalance(ACCOUNT));

        verify(repository, never()).replace(any(), any());
    }

    @Test
    void rebalanceWithoutTargetFails() {
        stored(new Portfolio(ACCOUNT));

        assertThrows(InvalidAllocationException.class, () -> service.rebalance(ACCOUNT));
    }

    @Test
    void rebalanceAndApplyExecutesPlanAndCommits() {
        Portfolio current = stored(new Portfolio(ACCOUNT).addStock("META", 10, bd("500"))
                .withTargetAllocation(TargetAllocation.of(Map.of("AAPL", bd("100")))));
        RebalancePlan plan = new RebalancePlan(
                List.of(new TradeAction("META", TradeSide.SELL, 10, bd("500"))),
                List.of(new TradeAction("AAPL", TradeSide.BUY, 26, bd("190"))));
        when(strategy.plan(any(), any(), any(), any())).thenReturn(plan);
        commitSucceedsOver(current);

        RebalancePlan result = service.rebalanceAndApply(ACCOUNT);

        assertEquals(plan, result);
        Portfolio committed = committedOver(current);
        assertTrue(committed.findStock("META").isEmpty());
        assertEquals(26, committed.findStock("AAPL").orElseThrow().quantity());
        assertEquals(0, bd("60").compareTo(committed.getCash()), "5,000 raised, 4,940 spent");
    }

    @Test
    void rebalanceAndApplyWithNothingToDoCommitsNothing() {
        stored(new Portfolio(ACCOUNT).addStock("META", 10, bd("500"))
                .withTargetAllocation(TargetAllocation.of(Map.of("META", bd("100")))));
        when(strategy.plan(any(), any(), any(), any())).thenReturn(RebalancePlan.empty());

        assertSame(RebalancePlan.empty(), service.rebalanceAndApply(ACCOUNT));

        verify(repository, never()).replace(any(), any());
    }

    @Test
    void rebalanceAndApplyRejectsAPlanSellingMoreThanIsHeldInTotal() {
        stored(new Portfolio(ACCOUNT).addStock("META", 10, bd("500"))
                .withTargetAllocation(TargetAllocation.of(Map.of("AAPL", bd("100")))));
        TradeAction sixMeta = new TradeAction("META", TradeSide.SELL, 6, bd("500"));
        when(strategy.plan(any(), any(), any(), any())).thenReturn(new RebalancePlan(List.of(sixMeta, sixMeta), List.of()));

        assertThrows(InsufficientQuantityException.class, () -> service.rebalanceAndApply(ACCOUNT));

        verify(repository, never()).replace(any(), any());
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
