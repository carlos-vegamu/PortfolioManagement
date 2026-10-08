package org.example.portfolio.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StockTest {

    @Test
    void normalizesTicker() {
        assertEquals("BRK.B", new Stock(" brk.b ", 1, BigDecimal.ONE).ticker());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "1ABC", "TOOLONGTICKER1", "AA PL", "A$"})
    void rejectsInvalidTickers(String ticker) {
        assertThrows(IllegalArgumentException.class, () -> new Stock(ticker, 1, BigDecimal.ONE));
    }

    @Test
    void rejectsNullTicker() {
        assertThrows(IllegalArgumentException.class, () -> new Stock(null, 1, BigDecimal.ONE));
    }

    @Test
    void rejectsNonPositiveQuantityOrPrice() {
        assertThrows(IllegalArgumentException.class, () -> new Stock("META", 0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Stock("META", 1, BigDecimal.ZERO));
        assertThrows(NullPointerException.class, () -> new Stock("META", 1, null));
    }

    @Test
    void costBasisIsQuantityTimesAveragePrice() {
        assertEquals(0, new BigDecimal("1250.50").compareTo(new Stock("META", 5, new BigDecimal("250.10")).costBasis()));
    }

    @Test
    void increaseByComputesWeightedAverage() {
        Stock result = new Stock("META", 3, new BigDecimal("10")).increaseBy(1, new BigDecimal("14"));

        assertEquals(4, result.quantity());
        assertEquals(0, new BigDecimal("11").compareTo(result.averagePurchasePrice()));
    }

    @Test
    void increaseByRoundsAverageToFourDecimals() {
        Stock result = new Stock("META", 1, new BigDecimal("10")).increaseBy(2, new BigDecimal("10.01"));

        assertEquals(new BigDecimal("10.0067"), result.averagePurchasePrice());
    }

    @Test
    void increaseByRejectsInvalidInput() {
        Stock stock = new Stock("META", 1, BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> stock.increaseBy(0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> stock.increaseBy(1, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> stock.increaseBy(1, null));
    }

    @Test
    void increaseByDetectsOverflow() {
        Stock stock = new Stock("META", Long.MAX_VALUE, BigDecimal.ONE);
        assertThrows(ArithmeticException.class, () -> stock.increaseBy(1, BigDecimal.ONE));
    }

    @Test
    void decreaseByKeepsAveragePrice() {
        Stock result = new Stock("META", 5, new BigDecimal("12.5")).decreaseBy(2);

        assertEquals(3, result.quantity());
        assertEquals(0, new BigDecimal("12.5").compareTo(result.averagePurchasePrice()));
    }

    @Test
    void decreaseByRejectsZeroNegativeOrFullQuantity() {
        Stock stock = new Stock("META", 5, BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> stock.decreaseBy(0));
        assertThrows(IllegalArgumentException.class, () -> stock.decreaseBy(-1));
        assertThrows(IllegalArgumentException.class, () -> stock.decreaseBy(5));
    }
}
