package org.example.portfolio.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.example.portfolio.api.PortfolioService;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.Stock;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.exception.PortfolioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Line-oriented command interpreter on top of {@link PortfolioService}. Input and output
 * streams are injected so the whole interface can be exercised from tests.
 */
public class PortfolioCli {

    private static final Logger LOG = LoggerFactory.getLogger(PortfolioCli.class);
    private static final String PROMPT = "portfolio> ";
    private static final String HELP = """
            Commands:
              create <account>                           create the account's portfolio (one per account)
              add <account> <ticker> <qty> <price>       buy shares; repeated buys update the average price
              sell <account> <ticker> <qty>              sell shares
              target <account> <TICKER=PCT> ...          set the target allocation, e.g. target acc1 META=40 AAPL=60
              show <account>                             list holdings and the target allocation
              allocation <account>                       current vs. target allocation
              rebalance <account> [apply]                list the buys/sells needed; 'apply' also executes them
              help                                       show this help
              exit | quit                                leave the program""";

    private final PortfolioService service;
    private final BufferedReader in;
    private final PrintStream out;

    public PortfolioCli(PortfolioService service, InputStream in, PrintStream out) {
        this.service = service;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    /** Reads commands until {@code exit} or end of input. */
    public void run() throws IOException {
        out.println("Portfolio Manager - type 'help' for commands.");
        out.print(PROMPT);
        out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            if (!execute(line)) {
                return;
            }
            out.print(PROMPT);
            out.flush();
        }
        out.println();
    }

    /**
     * Executes one command line.
     *
     * @return {@code false} when the user asked to exit
     */
    public boolean execute(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens[0].isEmpty()) {
            return true;
        }
        String command = tokens[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        LOG.info("CLI command: {}", line.trim());
        try {
            switch (command) {
                case "exit", "quit" -> {
                    out.println("Bye.");
                    return false;
                }
                case "help" -> out.println(HELP);
                case "create" -> create(args);
                case "add" -> add(args);
                case "sell" -> sell(args);
                case "target" -> target(args);
                case "show" -> show(args);
                case "allocation" -> allocation(args);
                case "rebalance" -> rebalance(args);
                default -> out.println("Unknown command '" + command + "'. Type 'help' for the list of commands.");
            }
        } catch (PortfolioException | IllegalArgumentException e) {
            LOG.warn("CLI command failed: '{}' -> {}", line.trim(), e.getMessage());
            out.println("Error: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Unexpected failure running '{}'", line.trim(), e);
            out.println("Unexpected error: " + e);
        }
        return true;
    }

    private void create(String[] args) {
        expect(args, 1, 1, "create <account>");
        PortfolioSnapshot p = service.createPortfolio(args[0]);
        out.println("Created portfolio for account " + p.accountId());
    }

    private void add(String[] args) {
        expect(args, 4, 4, "add <account> <ticker> <qty> <price>");
        PortfolioSnapshot p = service.addStock(args[0], args[1], parseLong(args[2], "quantity"), parseDecimal(args[3], "price"));
        String ticker = Stock.normalizeTicker(args[1]);
        Stock held = p.stocks().stream().filter(s -> s.ticker().equals(ticker)).findFirst().orElseThrow();
        out.printf(Locale.ROOT, "Bought %s %s. Holding: %d shares @ avg %s%n",
                args[2], ticker, held.quantity(), money(held.averagePurchasePrice()));
    }

    private void sell(String[] args) {
        expect(args, 3, 3, "sell <account> <ticker> <qty>");
        PortfolioSnapshot p = service.sellStock(args[0], args[1], parseLong(args[2], "quantity"));
        String ticker = Stock.normalizeTicker(args[1]);
        long left = p.stocks().stream().filter(s -> s.ticker().equals(ticker)).mapToLong(Stock::quantity).sum();
        out.printf(Locale.ROOT, "Sold %s %s. %d shares left%n", args[2], ticker, left);
    }

    private void target(String[] args) {
        expect(args, 2, Integer.MAX_VALUE, "target <account> <TICKER=PCT> ...");
        Map<String, BigDecimal> percentages = new TreeMap<>();
        for (String pair : Arrays.copyOfRange(args, 1, args.length)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Expected TICKER=PCT but got '" + pair + "'");
            }
            if (percentages.put(Stock.normalizeTicker(parts[0]), parseDecimal(parts[1], "percentage")) != null) {
                throw new IllegalArgumentException("Duplicate ticker in target: " + parts[0]);
            }
        }
        PortfolioSnapshot p = service.setTargetAllocation(args[0], percentages);
        out.println("Target allocation set: " + formatPercentages(p.targetAllocation()));
    }

    private void show(String[] args) {
        expect(args, 1, 1, "show <account>");
        PortfolioSnapshot p = service.getPortfolio(args[0]);
        out.println("Portfolio of account " + p.accountId());
        if (p.stocks().isEmpty()) {
            out.println("  (no stocks)");
        } else {
            out.printf(Locale.ROOT, "  %-8s %10s %14s %14s%n", "TICKER", "QUANTITY", "AVG PRICE", "COST BASIS");
            p.stocks().stream().sorted(Comparator.comparing(Stock::ticker)).forEach(s ->
                    out.printf(Locale.ROOT, "  %-8s %10d %14s %14s%n",
                            s.ticker(), s.quantity(), money(s.averagePurchasePrice()), money(s.costBasis())));
        }
        out.println("  Target: " + (p.targetAllocation().isEmpty() ? "(not set)" : formatPercentages(p.targetAllocation())));
    }

    private void allocation(String[] args) {
        expect(args, 1, 1, "allocation <account>");
        Map<String, BigDecimal> current = service.getCurrentAllocation(args[0]);
        Map<String, BigDecimal> target = service.getPortfolio(args[0]).targetAllocation();
        if (current.isEmpty() && target.isEmpty()) {
            out.println("Nothing to show: no stocks and no target allocation.");
            return;
        }
        Set<String> tickers = new TreeSet<>(current.keySet());
        tickers.addAll(target.keySet());
        out.printf(Locale.ROOT, "  %-8s %10s %10s %10s%n", "TICKER", "CURRENT %", "TARGET %", "DRIFT");
        for (String ticker : tickers) {
            BigDecimal cur = current.getOrDefault(ticker, BigDecimal.ZERO);
            BigDecimal tgt = target.getOrDefault(ticker, BigDecimal.ZERO);
            out.printf(Locale.ROOT, "  %-8s %10s %10s %+10.2f%n", ticker, pct(cur), pct(tgt), cur.subtract(tgt));
        }
    }

    private void rebalance(String[] args) {
        expect(args, 1, 2, "rebalance <account> [apply]");
        boolean apply = false;
        if (args.length == 2) {
            if (!args[1].equalsIgnoreCase("apply")) {
                throw new IllegalArgumentException("Unknown option '" + args[1] + "'. Usage: rebalance <account> [apply]");
            }
            apply = true;
        }
        RebalancePlan plan = apply ? service.rebalanceAndApply(args[0]) : service.rebalance(args[0]);
        if (plan.isEmpty()) {
            out.println("Portfolio is already balanced - nothing to do.");
            return;
        }
        out.println(apply ? "Rebalance executed:" : "Rebalance plan (use 'rebalance <account> apply' to execute):");
        printActions("SELL", plan.sells());
        printActions("BUY", plan.buys());
    }

    private void printActions(String label, List<TradeAction> actions) {
        for (TradeAction a : actions) {
            out.printf(Locale.ROOT, "  %-4s %6d %-8s @ %12s  (~%s)%n",
                    label, a.quantity(), a.ticker(), money(a.price()), money(a.value()));
        }
    }

    private static void expect(String[] args, int min, int max, String usage) {
        if (args.length < min || args.length > max) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }

    private static long parseLong(String raw, String what) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + what + " (whole number expected): " + raw);
        }
    }

    private static BigDecimal parseDecimal(String raw, String what) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + what + " (number expected): " + raw);
        }
    }

    private static String money(BigDecimal value) {
        return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String formatPercentages(Map<String, BigDecimal> percentages) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(percentages).forEach((ticker, value) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(value.stripTrailingZeros().toPlainString()).append("% ").append(ticker);
        });
        return sb.toString();
    }
}
