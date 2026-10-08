package org.example.portfolio.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.portfolio.exception.InvalidAllocationException;
import org.junit.jupiter.api.Test;

class TargetAllocationTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void acceptsAllocationSummingToOneHundred() {
        TargetAllocation allocation = TargetAllocation.of(Map.of("meta", bd("40"), "AAPL", bd("60")));

        assertEquals(List.of("AAPL", "META"), List.copyOf(allocation.tickers()));
        assertEquals(0, bd("40").compareTo(allocation.percentageFor("META")));
        assertEquals(0, bd("60").compareTo(allocation.percentageFor(" aapl ")));
    }

    @Test
    void acceptsDecimalPercentages() {
        TargetAllocation allocation = TargetAllocation.of(Map.of("META", bd("33.33"), "AAPL", bd("66.67")));
        assertEquals(2, allocation.asMap().size());
    }

    @Test
    void singleStockAtOneHundredPercentIsValid() {
        assertEquals(0, bd("100").compareTo(TargetAllocation.of(Map.of("META", bd("100"))).percentageFor("META")));
    }

    @Test
    void unknownTickerHasZeroPercent() {
        assertEquals(0, BigDecimal.ZERO.compareTo(TargetAllocation.of(Map.of("META", bd("100"))).percentageFor("TSLA")));
    }

    @Test
    void rejectsNullOrEmpty() {
        assertThrows(InvalidAllocationException.class, () -> TargetAllocation.of(null));
        assertThrows(InvalidAllocationException.class, () -> TargetAllocation.of(Map.of()));
    }

    @Test
    void rejectsSumDifferentFromOneHundred() {
        InvalidAllocationException low = assertThrows(InvalidAllocationException.class,
                () -> TargetAllocation.of(Map.of("META", bd("40"), "AAPL", bd("50"))));
        assertTrue(low.getMessage().contains("90"));
        assertThrows(InvalidAllocationException.class,
                () -> TargetAllocation.of(Map.of("META", bd("60"), "AAPL", bd("50"))));
    }

    @Test
    void rejectsNonPositiveOrOversizedPercentages() {
        assertThrows(InvalidAllocationException.class,
                () -> TargetAllocation.of(Map.of("META", bd("0"), "AAPL", bd("100"))));
        assertThrows(InvalidAllocationException.class,
                () -> TargetAllocation.of(Map.of("META", bd("-10"), "AAPL", bd("110"))));
        assertThrows(InvalidAllocationException.class,
                () -> TargetAllocation.of(Map.of("META", bd("101"))));
        Map<String, BigDecimal> withNull = new HashMap<>();
        withNull.put("META", null);
        assertThrows(InvalidAllocationException.class, () -> TargetAllocation.of(withNull));
    }

    @Test
    void rejectsTickersThatCollideAfterNormalisation() {
        assertThrows(InvalidAllocationException.class,
                () -> TargetAllocation.of(Map.of("meta", bd("50"), "META", bd("50"))));
    }

    @Test
    void rejectsInvalidTickerSymbols() {
        assertThrows(IllegalArgumentException.class, () -> TargetAllocation.of(Map.of("??", bd("100"))));
    }

    @Test
    void exposedMapIsUnmodifiable() {
        TargetAllocation allocation = TargetAllocation.of(Map.of("META", bd("100")));
        Map<String, BigDecimal> view = allocation.asMap();
        assertThrows(UnsupportedOperationException.class, () -> view.put("AAPL", BigDecimal.ONE));
    }

    @Test
    void equalityIgnoresBigDecimalScale() {
        TargetAllocation a = TargetAllocation.of(Map.of("META", bd("40"), "AAPL", bd("60")));
        TargetAllocation b = TargetAllocation.of(Map.of("META", bd("40.0"), "AAPL", bd("60.00")));
        TargetAllocation c = TargetAllocation.of(Map.of("META", bd("50"), "AAPL", bd("50")));
        TargetAllocation d = TargetAllocation.of(Map.of("META", bd("100")));
        TargetAllocation e = TargetAllocation.of(Map.of("META", bd("40"), "TSLA", bd("60")));

        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertNotEquals(a, e);
        assertNotEquals(a, "not an allocation");
        assertEquals("{AAPL=60, META=40}", a.toString());
    }
}
