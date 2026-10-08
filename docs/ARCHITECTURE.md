# Portfolio module – architecture

The diagrams below are Mermaid (rendered natively by GitHub, GitLab, IntelliJ and VS Code).
Pre-rendered SVG copies live in [`docs/diagrams/`](diagrams) for tools that don't render Mermaid.

| # | Diagram | Answers |
|---|---------|---------|
| 1 | [Layers and contracts](#1-layers-and-contracts) | Who talks to whom, which interfaces other modules consume or implement |
| 2 | [Domain model](#2-domain-model) | The entities, their relationships and the ports the domain depends on |
| 3 | [Rebalance flow](#3-rebalance-flow-rebalance-acc1-apply) | What happens at runtime for `rebalance <account> apply`, including concurrent updates |
| 4 | [Rebalance algorithm](#4-rebalance-algorithm-proportionalrebalancestrategy) | How the buy/sell list is computed |
| 5 | [Error handling](#5-error-handling) | Which failures exist and where they surface |

---

## 1. Layers and contracts

Dependencies point **inwards**: the CLI and the main application depend on the `api` contract; the service
depends on the domain, on the `spi` interfaces and on its own `MarketDataProvider` contract; the domain depends
on nothing outside itself. Concrete adapters (the mocks, the in-memory repository, the rebalance policy) are
chosen in one place, `Main`.

```mermaid
flowchart TB
    subgraph consumers["Consumers"]
        APP["Main application (other modules)"]
        CLI["PortfolioCli (this module's CLI)"]
    end

    API["PortfolioService  «interface, package api»<br/>the contract consumers depend on"]
    SVC["DefaultPortfolioService<br/>orchestrates the use cases"]

    subgraph domain["Domain"]
        PF["Portfolio (immutable aggregate root)<br/>Stock · TargetAllocation · RebalancePlan · TradeAction<br/>PortfolioSnapshot · AllocationReport · MarketPrices"]
        RS["RebalanceStrategy<br/>package domain"]
    end

    subgraph ports["Ports - interfaces the service depends on"]
        direction LR
        REPO["PortfolioRepository<br/>package spi"]
        ACC["AccountRepository<br/>package spi"]
        MDP["MarketDataProvider<br/>package service"]
    end

    subgraph adapters["Adapters - chosen in Main"]
        direction LR
        MEM["InMemoryPortfolioRepository"]
        MOCKACC["MockAccountRepository"]
        MOCKMKT["MockMarketDataProvider"]
        PRS["ProportionalRebalanceStrategy<br/>package strategy"]
    end

    subgraph external["External services - future real implementations"]
        direction LR
        DB[("Database")]
        ACCSVC["Account service"]
        MKTSVC["Market-data service"]
    end

    APP --> API
    CLI --> API
    API -.->|implemented by| SVC
    SVC --> PF
    SVC --> REPO
    SVC --> ACC
    SVC --> MDP
    PF --> RS

    REPO -.->|implemented by| MEM
    ACC -.->|implemented by| MOCKACC
    MDP -.->|implemented by| MOCKMKT
    RS -.->|implemented by| PRS

    MEM -.->|to be replaced by| DB
    MOCKACC -.->|to be replaced by| ACCSVC
    MOCKMKT -.->|to be replaced by| MKTSVC
```

**Reading guide**

- **Consumers** only ever see `PortfolioService` and immutable `PortfolioSnapshot`s / `AllocationReport`s.
- **`spi`** interfaces are what other modules implement to plug real persistence / accounts in.
  `MarketDataProvider` is the contract with the market-data service; it lives in `service`, its only user.
  The domain never calls it: the service wraps it in a `MarketPrices` (each ticker read once per operation)
  and passes that to `Portfolio`.
- **`RebalanceStrategy`** is the open/closed extension point: add a new policy by implementing it (in `strategy`)
  and passing it to `Portfolio.rebalance`; `Portfolio` does not change.

---

## 2. Domain model

```mermaid
classDiagram
    direction TB

    class Portfolio {
        <<immutable aggregate root>>
        -String accountId
        -SortedMap~String,Stock~ stocks
        -BigDecimal cash
        -TargetAllocation targetAllocation
        +addStock(ticker, quantity, price) Portfolio
        +sellStock(ticker, quantity) Portfolio
        +withTargetAllocation(allocation) Portfolio
        +getTotalValue(prices) BigDecimal
        +getCurrentAllocation(prices) AllocationReport
        +rebalance(strategy, prices) RebalancePlan
        +applyRebalance(plan) Portfolio
        +snapshot() PortfolioSnapshot
    }

    class Stock {
        <<record, immutable>>
        +String ticker
        +long quantity
        +BigDecimal averagePurchasePrice
        +costBasis() BigDecimal
        +increaseBy(qty, price) Stock
        +decreaseBy(qty) Stock
    }

    class TargetAllocation {
        <<value object>>
        -SortedMap~String,BigDecimal~ percentages
        +of(percentages)$ TargetAllocation
        +percentageFor(ticker) BigDecimal
        +tickers() Set
        +asMap() SortedMap
    }

    class RebalancePlan {
        <<record>>
        +List~TradeAction~ sells
        +List~TradeAction~ buys
        +proceeds() BigDecimal
        +cost() BigDecimal
        +isEmpty() boolean
    }

    class TradeAction {
        <<record>>
        +String ticker
        +TradeSide side
        +long quantity
        +BigDecimal price
        +value() BigDecimal
    }

    class TradeSide {
        <<enumeration>>
        BUY
        SELL
    }

    class PortfolioSnapshot {
        <<record, read-only copy>>
        +String accountId
        +SortedMap~String,Stock~ stocks
        +BigDecimal cash
        +Optional~TargetAllocation~ targetAllocation
    }

    class AllocationReport {
        <<record>>
        +SortedMap~String,BigDecimal~ current
        +BigDecimal cashPercentage
        +Optional~TargetAllocation~ target
    }

    class MarketPrices {
        <<one per operation>>
        +from(source)$ MarketPrices
        +of(prices)$ MarketPrices
        +priceOf(ticker) BigDecimal
    }

    class RebalanceStrategy {
        <<interface>>
        +plan(holdings, cash, target, prices) RebalancePlan
    }

    class ProportionalRebalanceStrategy {
        +plan(holdings, cash, target, prices) RebalancePlan
    }

    Portfolio "1" *-- "0..*" Stock : holds, one per ticker
    Portfolio "1" o-- "0..1" TargetAllocation : aims for
    Portfolio ..> RebalanceStrategy : plans with (method argument)
    Portfolio ..> MarketPrices : values with
    Portfolio ..> PortfolioSnapshot : creates
    Portfolio ..> AllocationReport : creates
    Portfolio ..> RebalancePlan : returns / applies
    RebalancePlan "1" *-- "0..*" TradeAction
    TradeAction --> TradeSide
    RebalanceStrategy ..> RebalancePlan : produces
    ProportionalRebalanceStrategy ..|> RebalanceStrategy
```

**Invariants enforced by the domain**

| Rule | Where |
|------|-------|
| Ticker is trimmed, upper-cased and matches `[A-Z][A-Z0-9.-]{0,9}` | `Stock.normalizeTicker` |
| Quantity is a positive whole number; price is positive (a null trade price is an `IllegalArgumentException` too) | `Stock`, `TradeAction`, `MarketPrices` |
| At most one position per ticker (re-buying merges, average price is the weighted average) | `Portfolio.addStock` |
| A position that is fully sold disappears; you cannot sell more than you hold | `Portfolio.sellStock` |
| Target percentages are in (0, 100], tickers are unique and the sum is **exactly 100**; stored without trailing zeros | `TargetAllocation.of` |
| Cash is never negative; only rebalancing changes it | `Portfolio.applyRebalance` |
| Rebalancing needs a target; `rebalance()` never changes anything; `applyRebalance()` checks the total sold per ticker against the holdings and the buys against cash + proceeds before changing anything, and is all-or-nothing | `Portfolio` |
| A plan contains only sells in `sells` and only buys in `buys` | `RebalancePlan` |
| **One portfolio per account** | `PortfolioRepository.saveIfAbsent` (atomic) |

**Design notes**

- `PortfolioSnapshot`, `AllocationReport` and `RebalancePlan` copy their collections on construction, whoever builds
  them. A returned snapshot or plan is a faithful record of the state it came from (audit, data integrity), which is
  worth the O(n) copy.
- `Portfolio` keeps identity equality on purpose: the in-memory compare-and-set compares instances, so an `equals`
  by account id would make every `replace` succeed and bring lost updates back. A database repository would compare
  a version instead.
- `applyRebalance` takes the plan's prices as given. Plans should come from the same portfolio at current prices, as
  `PortfolioService.rebalanceAndApply` guarantees; a hand-made plan selling at an invented price would credit
  invented cash.

---

## 3. Rebalance flow (`rebalance acc1 apply`)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant CLI as PortfolioCli
    participant Svc as DefaultPortfolioService
    participant Repo as PortfolioRepository
    participant PF as Portfolio
    participant Strat as RebalanceStrategy
    participant Mkt as MarketDataProvider

    User->>CLI: rebalance acc1 apply
    CLI->>Svc: rebalanceAndApply("acc1")
    Note over Svc: prices = new MarketPrices for this call,<br/>shared by every attempt below
    loop until the commit succeeds (at most 1,000 attempts, random exponential backoff between them)
        Svc->>Repo: findByAccountId("acc1")
        Repo-->>Svc: current Portfolio (or PortfolioNotFoundException)
        Svc->>PF: rebalance(strategy, prices)
        alt no target allocation defined
            PF-->>Svc: InvalidAllocationException
        else target defined
            PF->>Strat: plan(stocks, cash, target, prices)
            loop every ticker held or targeted
                Strat->>Mkt: getPrice(ticker), first time a ticker is needed only
                Mkt-->>Strat: price (or PriceUnavailableException)
            end
            Strat-->>PF: RebalancePlan (sells + buys)
            PF-->>Svc: RebalancePlan
        end
        Svc->>PF: applyRebalance(plan)
        Note over PF: check total sold per ticker and cash,<br/>then build a new Portfolio
        PF-->>Svc: updated Portfolio
        Svc->>Repo: replace(current, updated)
        Note over Svc,Repo: false if another thread committed first:<br/>re-read and plan again with the same prices
    end
    Note over Svc: still losing after 1,000 attempts:<br/>ConcurrentUpdateException
    Svc-->>CLI: RebalancePlan
    CLI-->>User: prints SELL / BUY lines and net cash

    Note over CLI,User: any PortfolioException is caught by the CLI<br/>and printed as "Error: …"
```

`rebalance <account>` (without `apply`) calls `PortfolioService.rebalance` instead: it reads the portfolio once,
returns the plan and commits nothing.

**Concurrency.** `Portfolio` is immutable, so a reader always sees one complete state, and a change can be
retried freely because building the new state has no side effects. `replace` is a compare-and-set (in memory, a
single `ConcurrentHashMap.replace(key, expected, updated)` call), so of two concurrent changes to one account
exactly one commits and the other is re-run on top of it: nothing is lost, and a plan is always applied to the
state it was computed from. No lock is held while prices are fetched, and prices are fetched once per call, not per
attempt, so a retry is pure computation: a slow market feed cannot make a rebalance lose every race against faster
writers. After each lost race the thread waits a random pause below a ceiling that doubles every time (64 ns up to
1 ms, "full jitter"), so writers racing for one account spread out instead of colliding again; an update still losing
after 1,000 attempts gives up with `ConcurrentUpdateException`, which is safe to retry.

---

## 4. Rebalance algorithm (`ProportionalRebalanceStrategy`)

```mermaid
flowchart TD
    A(["Input: holdings, cash, target, prices"])
    B{"No holdings and no cash?"}
    Z(["Empty plan - nothing to distribute"])

    subgraph setup["Setup - once"]
        C["Price every ticker held or targeted (once each)<br/>total = cash + sum of quantity x price"]
    end

    subgraph step1["Step 1 - for each ticker"]
        D["targetValue = total x target% / 100<br/>(ticker not in target: 0%)<br/>wanted = floor(targetValue / price)<br/>leftover = total - sum of wanted x price"]
    end

    subgraph step2["Step 2 - spend the leftover"]
        E["one more share for the underweight tickers whose gaps<br/>add up to the most the leftover affords<br/>(0/1 knapsack, branch and bound)"]
    end

    subgraph step3["Step 3 - don't sell into idle cash"]
        F["targeted tickers being sold:<br/>keep as many shares as the leftover still covers<br/>(drift unchanged, fewer trades)"]
    end

    L(["Plan: SELL held - wanted, BUY wanted - held<br/>buys <= cash + proceeds; the rest stays as cash"])

    A --> B
    B -- yes --> Z
    B -- no --> C
    C --> D
    D --> E
    E --> F
    F --> L
```

**Drift** is the total distance from the target: |value − target value| summed over every ticker and the cash
(whose target is zero). One more share of a ticker that is short of its target by `gap` (less than a share) lowers
the drift by 2 × gap and costs one share price, so step 2 is a 0/1 knapsack: the affordable set of extra shares with
the largest total gap. Taking the largest gap first is not enough, because one expensive share can block two cheaper
ones that close more (5 AAPL for META 33 / MSFT 33 / NVDA 34 ends 94.7% NVDA that way, instead of one META plus one
MSFT). Branch and bound over the tickers, sorted by gap per price and pruned with the fractional knapsack bound,
finds the best set exactly for typical portfolios of a few dozen stocks. Its search is capped at 10,000 steps, which
many tickers at very mixed prices can exceed; the plan then uses the best set found so far, never worse than taking
shares by gap per price. Beyond 1,000 underweight tickers the search is skipped altogether (a portfolio that large is
better served by an index fund). Step 3 does not change the drift, since unsold shares and idle
cash count the same, but saves trades. That is also why a ticker can stay more than one share over its target: when
no underweight ticker is affordable, selling it would only move money into cash.

All arithmetic is exact (`BigDecimal`), so the value of the holdings plus the cash is the same before and after a
plan is applied, and planning again at the same prices gives an empty plan. Among plans that keep at least the
whole shares fitting each target, the plan leaves the least drift whenever the search completes.

**Known limitation.** Giving up a share that fits one ticker's target, to fund a share of another, is not considered,
and can leave less drift: 2 META sold toward NVDA 80% / AMZN 20% at 900 / 180 keeps 2 AMZN and 64% cash, where giving
up the one AMZN share that fits would fund an NVDA and leave 10% cash. A wider search (allowing fewer shares than fit)
is a planned follow-up. Cost is O(n log n) for n tickers plus the bounded search, with one price lookup per ticker.

---

## 5. Error handling

All business-rule violations extend `PortfolioException` (unchecked).

```mermaid
classDiagram
    direction TB
    RuntimeException <|-- PortfolioException
    PortfolioException <|-- AccountNotFoundException
    PortfolioException <|-- PortfolioAlreadyExistsException
    PortfolioException <|-- PortfolioNotFoundException
    PortfolioException <|-- InvalidAllocationException
    PortfolioException <|-- InsufficientQuantityException
    PortfolioException <|-- InsufficientCashException
    PortfolioException <|-- PriceUnavailableException
    PortfolioException <|-- ConcurrentUpdateException
```

| Exception | Raised by | When |
|-----------|-----------|------|
| `AccountNotFoundException` | `DefaultPortfolioService` | `createPortfolio` for an account the `AccountRepository` does not know |
| `PortfolioAlreadyExistsException` | `DefaultPortfolioService` | `createPortfolio` for an account that already has one |
| `PortfolioNotFoundException` | `DefaultPortfolioService` | any operation on an account without a portfolio |
| `InvalidAllocationException` | `TargetAllocation`, `Portfolio` | percentages malformed or not summing to 100; `rebalance` without a target |
| `InsufficientQuantityException` | `Portfolio` | selling more shares than held, including a plan whose sells of one ticker add up to more than is held |
| `InsufficientCashException` | `Portfolio` | applying a plan whose buys cost more than the cash plus its sale proceeds |
| `PriceUnavailableException` | `MarketDataProvider` implementations, `MarketPrices` | no price for a ticker needed for valuation or rebalancing |
| `ConcurrentUpdateException` | `DefaultPortfolioService` | an update lost the compare-and-set race 1,000 times in a row; safe to retry |

**Propagation:** the domain throws, `DefaultPortfolioService` logs the failure **once** and rethrows, and the caller
decides what to do: `PortfolioCli` prints `Error: …` without logging again; the main application can handle them
as it sees fit. Malformed input (bad ticker, non-positive quantity or price) is an `IllegalArgumentException`,
handled the same way by the CLI.

Logging (SLF4J + Logback → `logs/portfolio.log`): the service logs every committed change at INFO, rejected
business rules at WARN, malformed input at INFO and unexpected failures at ERROR with their stack trace. `Portfolio`
does not log: it is an immutable value whose methods may run more than once when a concurrent update forces a
retry. The CLI logs every command it receives.
