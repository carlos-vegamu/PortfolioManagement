package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * How a portfolio's value is distributed right now, next to the distribution it aims for.
 * Both come from the same portfolio state, valued with one set of prices.
 *
 * @param current        share of the total value held in each stock, in percent (scale 2), sorted by ticker
 * @param cashPercentage share of the total value held as uninvested cash, in percent (scale 2)
 * @param target         the target allocation, if one has been defined
 */
public record AllocationReport(SortedMap<String, BigDecimal> current, BigDecimal cashPercentage,
                               Optional<TargetAllocation> target) {

    public AllocationReport {
        current = Collections.unmodifiableSortedMap(new TreeMap<>(current));
        Objects.requireNonNull(cashPercentage, "cashPercentage");
        Objects.requireNonNull(target, "target");
    }
}
