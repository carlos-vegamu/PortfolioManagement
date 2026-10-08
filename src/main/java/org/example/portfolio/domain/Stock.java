package org.example.portfolio.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An immutable position in a single stock: ticker, number of whole shares and the
 * average price paid per share.
 */
public record Stock(String ticker, long quantity, BigDecimal averagePurchasePrice) {

    private static final Pattern TICKER_FORMAT = Pattern.compile("[A-Z][A-Z0-9.\\-]{0,9}");
    private static final int PRICE_SCALE = 4;

    public Stock {
        ticker = normalizeTicker(ticker);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        Objects.requireNonNull(averagePurchasePrice, "averagePurchasePrice");
        if (averagePurchasePrice.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + averagePurchasePrice);
        }
    }

    /** Trims and upper-cases a ticker, rejecting anything that is not a plausible symbol. */
    public static String normalizeTicker(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Ticker must not be blank");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!TICKER_FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid ticker: " + raw);
        }
        return normalized;
    }

    /** Total amount paid for this position. */
    public BigDecimal costBasis() {
        return averagePurchasePrice.multiply(BigDecimal.valueOf(quantity));
    }

    /** Returns the position after buying {@code extraQuantity} more shares at {@code price}. */
    public Stock increaseBy(long extraQuantity, BigDecimal price) {
        if (extraQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + extraQuantity);
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + price);
        }
        long newQuantity = Math.addExact(quantity, extraQuantity);
        BigDecimal totalCost = costBasis().add(price.multiply(BigDecimal.valueOf(extraQuantity)));
        BigDecimal newAverage = totalCost.divide(BigDecimal.valueOf(newQuantity), PRICE_SCALE, RoundingMode.HALF_UP);
        return new Stock(ticker, newQuantity, newAverage);
    }

    /** Returns the position after selling {@code soldQuantity} shares; the average price is unchanged. */
    public Stock decreaseBy(long soldQuantity) {
        if (soldQuantity <= 0 || soldQuantity >= quantity) {
            throw new IllegalArgumentException(
                    "Sold quantity must be between 1 and " + (quantity - 1) + ": " + soldQuantity);
        }
        return new Stock(ticker, quantity - soldQuantity, averagePurchasePrice);
    }
}
