package org.example.portfolio.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

import org.example.portfolio.api.PortfolioService;
import org.example.portfolio.domain.AllocationReport;
import org.example.portfolio.domain.PortfolioSnapshot;
import org.example.portfolio.domain.RebalancePlan;
import org.example.portfolio.domain.Stock;
import org.example.portfolio.exception.InsufficientQuantityException;
import org.example.portfolio.exception.PortfolioAlreadyExistsException;
import org.example.portfolio.infra.InMemoryPortfolioRepository;
import org.example.portfolio.infra.MockAccountRepository;
import org.example.portfolio.infra.MockMarketDataProvider;
import org.example.portfolio.strategy.ProportionalRebalanceStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Many threads working on the same account at once, against the real in-memory adapters. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class DefaultPortfolioServiceConcurrencyTest {

    private static final int THREADS = 8;
    private static final String ACCOUNT = "acc";

    private final MockMarketDataProvider marketData = MockMarketDataProvider.withDefaultPrices();
    private final PortfolioService service = newService();
    private ExecutorService pool;

    private PortfolioService newService() {
        return new DefaultPortfolioService(new InMemoryPortfolioRepository(), new MockAccountRepository(), marketData,
                new ProportionalRebalanceStrategy());
    }

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void stopPool() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** Starts {@code task} on every thread at the same moment and rethrows the first failure. */
    private void onAllThreads(IntConsumer task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> running = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int thread = t;
            running.add(pool.submit(() -> {
                start.await();
                task.accept(thread);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : running) {
            future.get();
        }
    }

    /** Market value of the positions plus the cash. */
    private BigDecimal totalValue(String accountId) {
        PortfolioSnapshot snapshot = service.getPortfolio(accountId);
        BigDecimal total = snapshot.cash();
        for (Stock stock : snapshot.stocks().values()) {
            total = total.add(marketData.getPrice(stock.ticker()).multiply(BigDecimal.valueOf(stock.quantity())));
        }
        return total;
    }

    @Test
    void concurrentPurchasesAreNeverLost() throws Exception {
        service.createPortfolio(ACCOUNT);
        int perThread = 2_000;

        onAllThreads(thread -> {
            for (int i = 0; i < perThread; i++) {
                service.addStock(ACCOUNT, i % 2 == 0 ? "AAPL" : "MSFT", 1, BigDecimal.TEN);
            }
        });

        PortfolioSnapshot portfolio = service.getPortfolio(ACCOUNT);
        assertEquals(THREADS * perThread / 2, portfolio.stocks().get("AAPL").quantity());
        assertEquals(THREADS * perThread / 2, portfolio.stocks().get("MSFT").quantity());
    }

    @Test
    void concurrentSalesNeverSellMoreThanIsHeld() throws Exception {
        service.createPortfolio(ACCOUNT);
        service.addStock(ACCOUNT, "AAPL", 1_000, BigDecimal.TEN);
        int attemptsPerThread = 200;
        AtomicInteger sold = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        onAllThreads(thread -> {
            for (int i = 0; i < attemptsPerThread; i++) {
                try {
                    service.sellStock(ACCOUNT, "AAPL", 1);
                    sold.incrementAndGet();
                } catch (InsufficientQuantityException e) {
                    rejected.incrementAndGet();
                }
            }
        });

        assertEquals(1_000, sold.get());
        assertEquals(THREADS * attemptsPerThread - 1_000, rejected.get());
        assertTrue(service.getPortfolio(ACCOUNT).stocks().isEmpty());
    }

    @Test
    void exactlyOneOfManyConcurrentCreatesWinsAndNoneWipesItsHoldings() throws Exception {
        int accounts = 200;
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        onAllThreads(thread -> {
            for (int a = 0; a < accounts; a++) {
                try {
                    service.createPortfolio("acc-" + a);
                    created.incrementAndGet();
                } catch (PortfolioAlreadyExistsException e) {
                    rejected.incrementAndGet();
                }
                service.addStock("acc-" + a, "AAPL", 1, BigDecimal.TEN);
            }
        });

        assertEquals(accounts, created.get());
        assertEquals(accounts * (THREADS - 1), rejected.get());
        for (int a = 0; a < accounts; a++) {
            assertEquals(THREADS, service.getPortfolio("acc-" + a).stocks().get("AAPL").quantity(), "acc-" + a);
        }
    }

    @Test
    void readersAlwaysSeeAConsistentPortfolioWhileWritersTrade() throws Exception {
        service.createPortfolio(ACCOUNT);
        service.addStock(ACCOUNT, "AAPL", 100, BigDecimal.TEN);
        service.setTargetAllocation(ACCOUNT, Map.of("AAPL", bd("50"), "MSFT", bd("50")));

        onAllThreads(thread -> {
            for (int i = 0; i < 1_000; i++) {
                if (thread % 2 == 0) {
                    // every sale follows this thread's own purchase, so it can never be rejected
                    service.addStock(ACCOUNT, "MSFT", 1, BigDecimal.TEN);
                    service.sellStock(ACCOUNT, "MSFT", 1);
                } else {
                    PortfolioSnapshot snapshot = service.getPortfolio(ACCOUNT);
                    assertEquals(100, snapshot.stocks().get("AAPL").quantity());
                    snapshot.stocks().values().forEach(s -> assertTrue(s.quantity() > 0, s.toString()));
                    AllocationReport report = service.getCurrentAllocation(ACCOUNT);
                    BigDecimal sum = report.current().values().stream().reduce(report.cashPercentage(), BigDecimal::add);
                    assertTrue(sum.subtract(bd("100")).abs().compareTo(bd("0.05")) <= 0, "percentages add up to " + sum);
                }
            }
        });

        assertNull(service.getPortfolio(ACCOUNT).stocks().get("MSFT"));
    }

    @Test
    void concurrentRebalancesAndPurchasesKeepEveryCent() throws Exception {
        service.createPortfolio(ACCOUNT);
        service.addStock(ACCOUNT, "AAPL", 40, BigDecimal.ONE);
        service.addStock(ACCOUNT, "META", 10, BigDecimal.ONE);
        service.addStock(ACCOUNT, "TSLA", 25, BigDecimal.ONE);
        service.setTargetAllocation(ACCOUNT, Map.of("AAPL", bd("30"), "META", bd("30"), "NVDA", bd("40")));
        BigDecimal initialValue = totalValue(ACCOUNT);
        BigDecimal msftPrice = marketData.getPrice("MSFT");
        AtomicInteger msftBought = new AtomicInteger();

        onAllThreads(thread -> {
            for (int i = 0; i < 300; i++) {
                if (thread % 2 == 0) {
                    service.rebalanceAndApply(ACCOUNT);
                } else {
                    // MSFT is not in the target, so every rebalance sells it again
                    service.addStock(ACCOUNT, "MSFT", 1, msftPrice);
                    msftBought.incrementAndGet();
                }
            }
        });

        BigDecimal added = msftPrice.multiply(BigDecimal.valueOf(msftBought.get()));
        assertEquals(0, initialValue.add(added).compareTo(totalValue(ACCOUNT)),
                "rebalancing must neither create nor lose value, whatever the interleaving");
        service.rebalanceAndApply(ACCOUNT);
        assertTrue(service.rebalance(ACCOUNT).isEmpty(), "once quiet, one rebalance is enough");
    }

    @Test
    void rebalancesKeepUpWithConstantWritesBehindASlowMarketFeed() throws Exception {
        // every quote takes 2 ms while another thread commits a purchase every 0.2 ms: a rebalance that
        // fetched its prices again on every retry would lose the race forever
        Map<String, AtomicInteger> quotes = new ConcurrentHashMap<>();
        MarketDataProvider slowFeed = ticker -> {
            quotes.computeIfAbsent(ticker, t -> new AtomicInteger()).incrementAndGet();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            return marketData.getPrice(ticker);
        };
        PortfolioService contended = new DefaultPortfolioService(new InMemoryPortfolioRepository(),
                new MockAccountRepository(), slowFeed, new ProportionalRebalanceStrategy());
        contended.createPortfolio(ACCOUNT);
        contended.addStock(ACCOUNT, "AAPL", 40, BigDecimal.ONE);
        contended.addStock(ACCOUNT, "META", 10, BigDecimal.ONE);
        contended.addStock(ACCOUNT, "TSLA", 25, BigDecimal.ONE);
        contended.setTargetAllocation(ACCOUNT, Map.of("AAPL", bd("30"), "META", bd("30"), "NVDA", bd("20"), "GOOGL", bd("20")));
        AtomicBoolean stop = new AtomicBoolean();
        Future<?> writer = pool.submit(() -> {
            while (!stop.get()) {
                contended.addStock(ACCOUNT, "MSFT", 1, BigDecimal.TEN);
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
            }
            return null;
        });
        int rebalances = 20;

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                for (int i = 0; i < rebalances; i++) {
                    contended.rebalanceAndApply(ACCOUNT);
                }
            });
        } finally {
            stop.set(true);
            writer.get();
        }

        quotes.forEach((ticker, count) -> assertTrue(count.get() <= rebalances,
                ticker + " was quoted " + count + " times in " + rebalances + " rebalances"));
    }

    @Test
    void concurrentRebalancesOfOneAccountApplyTheSamePlanOnlyOnce() throws Exception {
        PortfolioService sequential = newService();
        for (PortfolioService s : List.of(service, sequential)) {
            s.createPortfolio(ACCOUNT);
            s.addStock(ACCOUNT, "TSLA", 37, BigDecimal.ONE);
            s.addStock(ACCOUNT, "AMZN", 11, BigDecimal.ONE);
            s.setTargetAllocation(ACCOUNT, Map.of("AAPL", bd("25"), "GOOGL", bd("35"), "NVDA", bd("40")));
        }
        RebalancePlan expected = sequential.rebalanceAndApply(ACCOUNT);
        AtomicInteger nonEmptyPlans = new AtomicInteger();

        onAllThreads(thread -> {
            if (!service.rebalanceAndApply(ACCOUNT).isEmpty()) {
                nonEmptyPlans.incrementAndGet();
            }
        });

        assertFalse(expected.isEmpty());
        assertEquals(1, nonEmptyPlans.get(), "every other thread finds the portfolio already balanced");
        assertEquals(sequential.getPortfolio(ACCOUNT), service.getPortfolio(ACCOUNT));
    }
}
