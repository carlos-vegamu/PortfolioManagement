package org.example.portfolio.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;

import org.example.portfolio.exception.PriceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketPricesTest {

    @Mock
    private Function<String, BigDecimal> source;

    @Test
    void eachTickerIsLookedUpOnceSoTheOperationSeesOneQuote() {
        when(source.apply("META")).thenReturn(new BigDecimal("500"), new BigDecimal("999"));
        MarketPrices prices = MarketPrices.from(source);

        assertEquals(new BigDecimal("500"), prices.priceOf("META"));
        assertEquals(new BigDecimal("500"), prices.priceOf("META"));

        verify(source, times(1)).apply("META");
    }

    @Test
    void fixedPricesRejectUnknownTickers() {
        MarketPrices prices = MarketPrices.of(Map.of("META", new BigDecimal("500")));

        assertEquals(new BigDecimal("500"), prices.priceOf("META"));
        assertThrows(PriceUnavailableException.class, () -> prices.priceOf("AAPL"));
    }

    @Test
    void missingOrNonPositiveQuotesAreRejected() {
        when(source.apply("META")).thenReturn(null);
        when(source.apply("AAPL")).thenReturn(BigDecimal.ZERO);
        MarketPrices prices = MarketPrices.from(source);

        assertThrows(PriceUnavailableException.class, () -> prices.priceOf("META"));
        assertThrows(IllegalStateException.class, () -> prices.priceOf("AAPL"));
    }
}
