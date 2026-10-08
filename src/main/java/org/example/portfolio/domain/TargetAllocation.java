package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.example.portfolio.exception.InvalidAllocationException;

/**
 * The distribution a portfolio is aiming for, expressed as percentages per ticker
 * (e.g. 40% META, 60% AAPL). Percentages must be positive and add up to exactly 100.
 * They are stored without trailing zeros, so 40 and 40.00 are the same allocation.
 */
public final class TargetAllocation {

    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SortedMap<String, BigDecimal> percentages;

    private TargetAllocation(SortedMap<String, BigDecimal> percentages) {
        this.percentages = Collections.unmodifiableSortedMap(percentages);
    }

    /**
     * Validates and builds an allocation.
     *
     * @throws InvalidAllocationException if empty, if a percentage is not in (0, 100],
     *                                    if a ticker appears twice after normalisation or if the sum is not 100
     */
    public static TargetAllocation of(Map<String, BigDecimal> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new InvalidAllocationException("Target allocation must contain at least one stock");
        }
        SortedMap<String, BigDecimal> normalized = new TreeMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : requested.entrySet()) {
            String ticker = Stock.normalizeTicker(entry.getKey());
            BigDecimal pct = entry.getValue();
            if (pct == null || pct.signum() <= 0 || pct.compareTo(HUNDRED) > 0) {
                throw new InvalidAllocationException(
                        "Percentage for " + ticker + " must be greater than 0 and at most 100: " + pct);
            }
            if (normalized.put(ticker, canonical(pct)) != null) {
                throw new InvalidAllocationException("Duplicate ticker in allocation: " + ticker);
            }
            sum = sum.add(pct);
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw new InvalidAllocationException(
                    "Allocation percentages must add up to 100 but add up to " + sum.stripTrailingZeros().toPlainString());
        }
        return new TargetAllocation(normalized);
    }

    /** Target percentage for a ticker; zero when the ticker is not part of the allocation. */
    public BigDecimal percentageFor(String ticker) {
        return percentages.getOrDefault(Stock.normalizeTicker(ticker), BigDecimal.ZERO);
    }

    /** Tickers in the allocation, sorted. */
    public Set<String> tickers() {
        return percentages.keySet();
    }

    /** Unmodifiable view, sorted by ticker. */
    public SortedMap<String, BigDecimal> asMap() {
        return percentages;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof TargetAllocation other && percentages.equals(other.percentages));
    }

    @Override
    public int hashCode() {
        return percentages.hashCode();
    }

    @Override
    public String toString() {
        return percentages.toString();
    }

    /** Drops trailing zeros without switching to exponent notation (100 stays 100, not 1E+2). */
    private static BigDecimal canonical(BigDecimal pct) {
        BigDecimal stripped = pct.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
