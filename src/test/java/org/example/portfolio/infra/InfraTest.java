package org.example.portfolio.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;

import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.exception.PriceUnavailableException;
import org.junit.jupiter.api.Test;

class InfraTest {

    @Test
    void repositoryKeepsOnlyTheFirstPortfolioOfAnAccount() {
        InMemoryPortfolioRepository repository = new InMemoryPortfolioRepository();
        assertTrue(repository.findByAccountId("acc").isEmpty());

        Portfolio first = new Portfolio("acc");
        assertTrue(repository.saveIfAbsent(first));
        assertFalse(repository.saveIfAbsent(new Portfolio("acc")));

        assertSame(first, repository.findByAccountId("acc").orElseThrow());
    }

    @Test
    void repositoryReplaceIsACompareAndSet() {
        InMemoryPortfolioRepository repository = new InMemoryPortfolioRepository();
        Portfolio first = new Portfolio("acc");
        repository.saveIfAbsent(first);
        Portfolio second = first.addStock("META", 1, BigDecimal.ONE);
        Portfolio staleUpdate = first.addStock("AAPL", 1, BigDecimal.ONE);

        assertTrue(repository.replace(first, second));
        assertFalse(repository.replace(first, staleUpdate), "first is no longer the stored portfolio");

        assertSame(second, repository.findByAccountId("acc").orElseThrow());
    }

    @Test
    void repositoryRefusesToReplaceAPortfolioWithAnotherAccounts() {
        InMemoryPortfolioRepository repository = new InMemoryPortfolioRepository();
        Portfolio first = new Portfolio("acc");
        repository.saveIfAbsent(first);
        Portfolio other = new Portfolio("other");

        assertThrows(IllegalArgumentException.class, () -> repository.replace(first, other));
    }

    @Test
    void defaultAccountRepositoryAcceptsAnyNonBlankId() {
        MockAccountRepository accounts = new MockAccountRepository();

        assertTrue(accounts.exists("anything"));
        assertFalse(accounts.exists(" "));
        assertFalse(accounts.exists(null));
    }

    @Test
    void restrictedAccountRepositoryOnlyKnowsGivenIds() {
        MockAccountRepository accounts = new MockAccountRepository(Set.of("a", "b"));

        assertTrue(accounts.exists("a"));
        assertFalse(accounts.exists("c"));
    }

    @Test
    void accountRepositoryCanUseAnyPredicateButStillRejectsBlankIds() {
        MockAccountRepository accounts = new MockAccountRepository(id -> id.startsWith("acc-") || id.isBlank());

        assertTrue(accounts.exists("acc-7"));
        assertFalse(accounts.exists("guest"));
        assertFalse(accounts.exists("  "));
    }

    @Test
    void marketDataReturnsConfiguredPrices() {
        MockMarketDataProvider provider = MockMarketDataProvider.withDefaultPrices();

        assertEquals(0, new BigDecimal("500.00").compareTo(provider.getPrice("META")));

        provider.setPrice("META", new BigDecimal("510"));
        assertEquals(0, new BigDecimal("510").compareTo(provider.getPrice("META")));
    }

    @Test
    void marketDataFailsForUnknownTickerAndBadPrices() {
        MockMarketDataProvider provider = new MockMarketDataProvider();

        PriceUnavailableException e = assertThrows(PriceUnavailableException.class, () -> provider.getPrice("ZZZZ"));
        assertTrue(e.getMessage().contains("ZZZZ"));
        assertThrows(IllegalArgumentException.class, () -> provider.setPrice("META", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> provider.setPrice("META", null));
    }
}
