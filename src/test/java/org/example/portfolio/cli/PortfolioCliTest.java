package org.example.portfolio.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import org.example.portfolio.api.PortfolioService;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.TradeAction;
import org.example.portfolio.domain.TradeSide;
import org.example.portfolio.infra.InMemoryPortfolioRepository;
import org.example.portfolio.infra.MockAccountRepository;
import org.example.portfolio.infra.MockMarketDataProvider;
import org.example.portfolio.service.DefaultPortfolioService;
import org.example.portfolio.strategy.ProportionalRebalanceStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PortfolioCliTest {

    private ByteArrayOutputStream buffer;
    private PortfolioCli cli;

    @BeforeEach
    void setUp() {
        PortfolioService service = new DefaultPortfolioService(
                new InMemoryPortfolioRepository(),
                new MockAccountRepository(),
                MockMarketDataProvider.withDefaultPrices(),
                new ProportionalRebalanceStrategy());
        cli = cliFor(service, "");
    }

    private PortfolioCli cliFor(PortfolioService service, String input) {
        buffer = new ByteArrayOutputStream();
        return new PortfolioCli(service,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }

    private String run(String... commands) {
        buffer.reset();
        for (String command : commands) {
            cli.execute(command);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ---- end-to-end flow ---------------------------------------------------

    @Test
    void fullScenarioCreateAddTargetAllocationAndRebalance() {
        String out = run(
                "create acc1",
                "add acc1 meta 10 480",
                "add acc1 AAPL 50 150",
                "target acc1 META=40 AAPL=60",
                "allocation acc1",
                "rebalance acc1");

        assertTrue(out.contains("Created portfolio for account acc1"), out);
        assertTrue(out.contains("Holding: 10 shares @ avg $480.00"), out);
        assertTrue(out.contains("Target allocation set: 60% AAPL, 40% META"), out);
        assertTrue(out.contains("34.48%"), out);
        assertTrue(out.contains("SELL"), out);
        assertTrue(out.contains("BUY"), out);
        assertTrue(out.contains("Rebalance plan"), out);

        String applied = run("rebalance acc1 apply", "show acc1", "rebalance acc1");
        assertTrue(applied.contains("Rebalance executed"), applied);
        assertTrue(applied.contains("META"), applied);
        // after applying, the next plan is empty (or at most whole-share noise); the table must still render
        assertTrue(applied.contains("QUANTITY"), applied);
    }

    @Test
    void rebalanceKeepsUnspentProceedsAsCash() {
        String out = run("create acc1", "add acc1 AAPL 15 190", "target acc1 AAPL=50 NVDA=50",
                "rebalance acc1 apply", "show acc1", "allocation acc1", "rebalance acc1");

        assertTrue(out.contains("SELL      5 AAPL"), out);
        assertTrue(out.contains("BUY       1 NVDA"), out);
        assertTrue(out.contains("Net cash: +$50.00"), out);
        assertTrue(out.contains("Cash: $50.00"), out);
        assertTrue(out.contains("(cash)"), out);
        assertTrue(out.contains("already balanced"), out);
    }

    @Test
    void rebalanceNeverSellsSharesItCannotReinvest() {
        String out = run("create acc1", "add acc1 AAPL 3 190", "target acc1 AAPL=50 NVDA=50", "rebalance acc1 apply", "show acc1");

        assertTrue(out.contains("already balanced"), out);
        assertTrue(out.contains("AAPL              3"), out);
    }

    @Test
    void secondPortfolioForSameAccountIsRejected() {
        String out = run("create acc1", "create acc1");

        assertTrue(out.contains("Error: Account acc1 already has a portfolio"), out);
    }

    @Test
    void buyingTheSameStockTwiceShowsWeightedAverage() {
        String out = run("create acc1", "add acc1 META 100 10", "add acc1 META 100 20");

        assertTrue(out.contains("Holding: 200 shares @ avg $15.00"), out);
    }

    @Test
    void tradesEchoTheParsedQuantity() {
        String out = run("create acc1", "add acc1 META 007 10", "sell acc1 META 02");

        assertTrue(out.contains("Bought 7 META. Holding: 7 shares"), out);
        assertTrue(out.contains("Sold 2 META. 5 shares left"), out);
    }

    @Test
    void sellReportsRemainingShares() {
        String out = run("create acc1", "add acc1 META 10 500", "sell acc1 META 4", "sell acc1 META 6", "sell acc1 META 1");

        assertTrue(out.contains("Sold 4 META. 6 shares left"), out);
        assertTrue(out.contains("Sold 6 META. 0 shares left"), out);
        assertTrue(out.contains("Error: Cannot sell 1 META: only 0 held"), out);
    }

    @Test
    void showListsHoldingsAndTarget() {
        String out = run("create acc1", "show acc1", "add acc1 META 2 500", "target acc1 META=100", "show acc1");

        assertTrue(out.contains("(no stocks)"), out);
        assertTrue(out.contains("(not set)"), out);
        assertTrue(out.contains("Cash: $0.00"), out);
        assertTrue(out.contains("$1000.00"), out);
        assertTrue(out.contains("Target: 100% META"), out);
    }

    @Test
    void allocationOfEmptyPortfolioWithoutTargetSaysSo() {
        assertTrue(run("create acc1", "allocation acc1").contains("Nothing to show"));
    }

    @Test
    void allocationListsTargetOnlyTickersWithZeroCurrent() {
        String out = run("create acc1", "add acc1 META 1 500", "target acc1 META=50 AAPL=50", "allocation acc1");

        assertTrue(out.contains("AAPL"), out);
        assertTrue(out.contains("0.00%"), out);
        assertTrue(out.contains("-50.00"), out);
    }

    @Test
    void balancedPortfolioReportsNothingToDo() {
        String out = run("create acc1", "add acc1 META 1 500", "target acc1 META=100", "rebalance acc1");

        assertTrue(out.contains("already balanced"), out);
    }

    // ---- error handling ----------------------------------------------------

    @Test
    void domainAndParsingErrorsAreReportedNotThrown() {
        String out = run(
                "show nobody",
                "create acc1",
                "add acc1 META ten 5",
                "add acc1 META 5 cheap",
                "add acc1 ??? 5 5",
                "add acc1 META 0 5",
                "target acc1 META=40 AAPL=50",
                "target acc1 META",
                "target acc1 META=40 META=60",
                "target acc1 META=abc",
                "rebalance acc1",
                "rebalance acc1 now",
                "rebalance acc1 apply");

        assertTrue(out.contains("Error: Account nobody has no portfolio"), out);
        assertTrue(out.contains("Error: Invalid quantity (whole number expected): ten"), out);
        assertTrue(out.contains("Error: Invalid price (number expected): cheap"), out);
        assertTrue(out.contains("Error: Invalid ticker: ???"), out);
        assertTrue(out.contains("Error: Quantity must be positive: 0"), out);
        assertTrue(out.contains("add up to 90"), out);
        assertTrue(out.contains("Expected TICKER=PCT but got 'META'"), out);
        assertTrue(out.contains("Duplicate ticker in target: META"), out);
        assertTrue(out.contains("Invalid percentage (number expected): abc"), out);
        assertTrue(out.contains("Define a target allocation before rebalancing"), out);
        assertTrue(out.contains("Unknown option 'now'"), out);
    }

    @Test
    void missingPriceIsReported() {
        String out = run("create acc1", "add acc1 ZZZZ 5 5", "allocation acc1");

        assertTrue(out.contains("Error: No market price available for ZZZZ"), out);
    }

    @Test
    void wrongArgumentCountShowsUsage() {
        String out = run("create", "add acc1", "sell acc1", "target acc1", "show", "allocation", "rebalance", "rebalance a b c");

        assertTrue(out.contains("Usage: create <account>"), out);
        assertTrue(out.contains("Usage: add <account> <ticker> <qty> <price>"), out);
        assertTrue(out.contains("Usage: sell <account> <ticker> <qty>"), out);
        assertTrue(out.contains("Usage: target <account> <TICKER=PCT> ..."), out);
        assertTrue(out.contains("Usage: show <account>"), out);
        assertTrue(out.contains("Usage: allocation <account>"), out);
        assertTrue(out.contains("Usage: rebalance <account> [apply]"), out);
    }

    @Test
    void unexpectedFailuresDoNotCrashTheShell() {
        PortfolioService broken = mock(PortfolioService.class);
        when(broken.getPortfolio("acc")).thenThrow(new IllegalStateException("boom"));
        PortfolioCli brokenCli = cliFor(broken, "");

        assertTrue(brokenCli.execute("show acc"));

        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("Unexpected error"));
    }

    // ---- shell behaviour ---------------------------------------------------

    @Test
    void helpAndUnknownCommands() {
        String out = run("help", "dance");

        assertTrue(out.contains("create <account>"), out);
        assertTrue(out.contains("Unknown command 'dance'"), out);
    }

    @Test
    void blankLinesAreIgnoredAndExitStopsTheShell() {
        assertTrue(cli.execute("   "));
        assertFalse(cli.execute("exit"));
        assertFalse(cli.execute("QUIT"));
    }

    @Test
    void runLoopProcessesInputUntilExit() throws IOException {
        PortfolioService service = mock(PortfolioService.class);
        when(service.createPortfolio("acc")).thenReturn(
                new PortfolioSnapshot("acc", new TreeMap<>(), BigDecimal.ZERO, Optional.empty()));
        PortfolioCli looping = cliFor(service, "create acc\nexit\ncreate never\n");

        looping.run();

        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Portfolio Manager"), out);
        assertTrue(out.contains("Created portfolio for account acc"), out);
        assertTrue(out.contains("Bye."), out);
        verify(service).createPortfolio("acc");
    }

    @Test
    void runLoopEndsOnEndOfInput() throws IOException {
        PortfolioCli looping = cliFor(mock(PortfolioService.class), "help\n");

        looping.run();

        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("Commands:"));
    }

    @Test
    void rebalanceOutputUsesServicePlanVerbatim() {
        PortfolioService service = mock(PortfolioService.class);
        when(service.rebalance("acc")).thenReturn(new RebalancePlan(
                List.of(new TradeAction("META", TradeSide.SELL, 2, new BigDecimal("500"))),
                List.of(new TradeAction("AAPL", TradeSide.BUY, 5, new BigDecimal("190")))));
        PortfolioCli custom = cliFor(service, "");

        custom.execute("rebalance acc");

        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.indexOf("SELL") < out.indexOf("BUY"), "sells are listed before buys: " + out);
        assertTrue(out.contains("~$1000.00"), out);
        assertTrue(out.contains("~$950.00"), out);
        assertTrue(out.contains("Net cash: +$50.00"), out);
    }
}
