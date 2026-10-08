# Portfolio Management module

Portfolio module of a personal investments and trading app: one portfolio per account, holding
stocks (ticker, quantity, average purchase price) and a target allocation, with a rebalance
operation that says what to buy and sell. Ships with an interactive CLI.

## Quick start

```
make build      # compile + package target/portfolio-cli.jar (tests skipped)
make run        # interactive CLI
make test       # JUnit 5 + Mockito unit tests
make coverage   # tests + JaCoCo; fails if Portfolio coverage < 80%  (target/site/jacoco/index.html)
make clean
```

Requires Maven and a JDK 17+. On macOS the Makefile picks a JDK 17 automatically when one is installed.
Logs (SLF4J + Logback) are written to `logs/portfolio.log`, not to the console, so they don't mix with the CLI output.

## CLI

```
create <account>                      create the account's portfolio (one per account)
add <account> <ticker> <qty> <price>  buy shares; repeated buys update the average price
sell <account> <ticker> <qty>         sell shares
target <account> <TICKER=PCT> ...     e.g. target acc1 META=40 AAPL=60   (must add up to 100)
show <account>                        holdings, cash and target
allocation <account>                  current vs. target allocation
rebalance <account> [apply]           show the buys/sells needed; 'apply' also executes them
help | exit
```

The mock market data knows AAPL, AMZN, GOOGL, META, MSFT, NVDA and TSLA.
Storage is in memory, so data is lost when the CLI exits.

## Design

Full diagrams (layers, domain model, rebalance flow, algorithm, error handling) are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md); SVG copies are in [`docs/diagrams/`](docs/diagrams).

![Layers and contracts](docs/diagrams/1-layers-and-contracts.svg)


```
org.example.portfolio
├── api       PortfolioService            contract consumed by the main application
├── spi       PortfolioRepository,        contracts implemented by other modules (persistence, accounts)
│             AccountRepository
├── domain    Portfolio (immutable aggregate), Stock, TargetAllocation, RebalancePlan, TradeAction,
│             PortfolioSnapshot, AllocationReport, MarketPrices, RebalanceStrategy (policy port)
├── service   DefaultPortfolioService, MarketDataProvider (contract with the market-data service)
├── strategy  ProportionalRebalanceStrategy
├── infra     InMemoryPortfolioRepository, MockMarketDataProvider, MockAccountRepository   (mocks of external services)
├── cli       PortfolioCli
└── exception PortfolioException and subclasses
org.example.Main                          composition root
```

- **SRP** – `Portfolio` guards its own invariants; use-case orchestration lives in the service, the buy/sell policy in a strategy, I/O in the CLI.
- **OCP** – new rebalancing policies implement `RebalanceStrategy` and are passed to `Portfolio.rebalance`; `Portfolio` doesn't change.
- **LSP / ISP** – small, focused interfaces (`MarketDataProvider` has one method, `AccountRepository` has one); every implementation, mocks included, honours the documented contract.
- **DIP** – `Portfolio`, the service and the CLI depend on interfaces only; `Main` is the only place that picks implementations.
- **Testability** – the strategy and the prices are method arguments, so `Portfolio` is tested with Mockito mocks of `RebalanceStrategy` and of a price source. The CLI takes its input/output streams as constructor arguments.
- The service returns immutable `PortfolioSnapshot`s so callers can't mutate the aggregate behind its back.

### Thread safety

`PortfolioService` is safe to call from several threads. `Portfolio` is immutable: every change returns a new
instance, so a change that fails half-way has no effect. The service commits a change with
`PortfolioRepository.replace(expected, updated)`, a compare-and-set; if another thread committed first, the change
is re-run on the newer state. `createPortfolio` uses `saveIfAbsent`. The in-memory repository implements both with
single atomic `ConcurrentHashMap` calls, so no locks are held while prices are fetched. Each operation reads every
price at most once (`MarketPrices`), so all its calculations use the same quotes.

### Rebalancing rules (`ProportionalRebalanceStrategy`)

- Self-financing and value-conserving: the market value of the holdings plus the portfolio's cash is redistributed per the target percentages. Buys never cost more than the cash plus the sale proceeds, and whatever whole shares can't use stays in the portfolio as **cash**.
- Whole shares only: each ticker first gets as many shares as fit in its target value; the leftover then buys one more share of the most underweight tickers (largest gap first) while it can afford them; anything still left cancels sells, so shares are never sold just to sit as cash.
- Holdings not in the target are sold completely.
- Rebalancing again right after applying a plan (same prices) finds nothing to do. Small drift from the exact target remains, bounded by one share per ticker.
- An empty portfolio without cash yields an empty plan (nothing to distribute).
- `add` and `sell` record trades settled outside the portfolio and don't touch the cash; only rebalancing does.
- `rebalance` only computes the plan; `rebalance <account> apply` (or `PortfolioService.rebalanceAndApply`) also executes it, atomically and all-or-nothing: the plan is rejected if it sells more shares of a ticker, in total, than are held, or buys more than the cash can pay for.

## Tests and coverage

141 unit tests, including concurrency tests (many threads on one account) and a seeded property test that checks every rebalance keeps the portfolio's value and leaves nothing to do on a second run. `Portfolio` is at 100% line, branch and method coverage, and the build enforces a minimum of 80% (`make coverage`).

---

## Original requirements

`You’re building a portfolio management module, part of a personal investments and trading app

Entities and Services of this module will be consumed by the main application, but also they will provide context data to initialize it or perform operations for the moment mock those external consumer services.
- Provide a CLI interface to interact with the Portfolio module, allowing users to create a Portfolio, add Stocks, view the current allocation, and perform rebalancing operations.
- Every account can have just one Portfolio.
- Portfolio assets can be just Stocks.
- Every Portfolio should have a Set of Stocks (Ticker, Quantity, Average Purchase Price).
- Portfolio class has a collection of “allocated” Stocks that represents the distribution of the Stocks the Portfolio is aiming (i.e. 40% META, 60% APPL)
- Provide a portfolio rebalance method to know which Stocks should be sold and which ones should be bought to have a balanced Portfolio based on the portfolio’s allocation.
- log operations and errors using a logging framework (e.g., SLF4J).


Use SOLID principles to design the module and provide interfaces to contract the other modules. The Portfolio class should be designed to be easily testable and maintainable.

Unit Test

- Perform 80% code coverage for the Portfolio class and its methods.
- Use JUnit for testing and Mockito for mocking dependencies.

Shipment Instructions:
Generate a Makefile to compile and run the application, as well as to execute the unit tests. The Makefile should include targets for building the project, running the CLI interface, and executing the unit tests.`