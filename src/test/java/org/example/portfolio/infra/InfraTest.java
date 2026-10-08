package org.example.portfolio.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;

import org.example.portfolio.domain.Portfolio;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.exception.PriceUnavailableException;
import org.junit.jupiter.api.Test;

class InfraTest {

    @Test
    void repositoryStoresAndReplacesByAccountId() {
        InMemoryPortfolioRepository repository = new InMemoryPortfolioRepository();
        assertTrue(repository.findByAccountId("acc").isEmpty());

        Portfolio first = new Portfolio("acc", (h, t, p) -> RebalancePlan.empty());
        Portfolio second = new Portfolio("acc", (h, t, p) -> RebalancePlan.empty());
        repository.save(first);
        assertEquals(first, repository.findByAccountId("acc").orElseThrow());

        repository.save(second);
        assertEquals(second, repository.findByAccountId("acc").orElseThrow());
    }

    @Test
    void defaultAccountDirectoryAcceptsAnyNonBlankId() {
        MockAccountDirectory directory = new MockAccountDirectory();

        assertTrue(directory.exists("anything"));
        assertFalse(directory.exists(" "));
        assertFalse(directory.exists(null));
    }

    @Test
    void restrictedAccountDirectoryOnlyKnowsGivenIds() {
        MockAccountDirectory directory = new MockAccountDirectory(Set.of("a", "b"));

        assertTrue(directory.exists("a"));
        assertFalse(directory.exists("c"));
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
