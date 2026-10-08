# Portfolio module – architecture

The diagrams below are Mermaid (rendered natively by GitHub, GitLab, IntelliJ and VS Code).
Pre-rendered SVG copies live in [`docs/diagrams/`](diagrams) for tools that don't render Mermaid.

| # | Diagram | Answers |
|---|---------|---------|
| 1 | [Layers and contracts](#1-layers-and-contracts) | Who talks to whom, which interfaces other modules consume or implement |
| 2 | [Domain model](#2-domain-model) | The entities, their relationships and the ports the domain depends on |
| 3 | [Rebalance flow](#3-rebalance-flow-rebalance-acc1-apply) | What happens at runtime for `rebalance <account> apply` |
| 4 | [Rebalance algorithm](#4-rebalance-algorithm-proportionalrebalancestrategy) | How the buy/sell list is computed |
| 5 | [Error handling](#5-error-handling) | Which failures exist and where they surface |

---

## 1. Layers and contracts

Dependencies point **inwards**: the CLI and the main application depend on the `api` contract; the service
depends on the domain and on `spi` interfaces; the domain depends only on its own ports.
Concrete adapters (the mocks, the in-memory repository, the rebalance policy) are chosen in one place, `Main`.

```mermaid
flowchart TB
    subgraph consumers["Consumers"]
        APP["Main application (other modules)"]
        CLI["PortfolioCli (this module's CLI)"]
    end

    API["PortfolioService  «interface, package api»<br/>the contract consumers depend on"]
    SVC["DefaultPortfolioService<br/>orchestrates the use cases"]

    subgraph domain["Domain"]
        PF["Portfolio (aggregate root)<br/>Stock · TargetAllocation · RebalancePlan<br/>TradeAction · PortfolioSnapshot"]
    end

    subgraph ports["Ports - interfaces the module depends on"]
        direction LR
        REPO["PortfolioRepository<br/>package spi"]
        ACC["AccountDirectory<br/>package spi"]
        MDP["MarketDataProvider<br/>package domain"]
        RS["RebalanceStrategy<br/>package domain"]
    end

    subgraph adapters["Adapters - chosen in Main"]
        direction LR
        MEM["InMemoryPortfolioRepository"]
        MOCKACC["MockAccountDirectory"]
        MOCKMKT["MockMarketDataProvider"]
        PRS["ProportionalRebalanceStrategy"]
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
    PF --> MDP
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

- **Consumers** only ever see `PortfolioService` and immutable `PortfolioSnapshot`s – never the mutable `Portfolio`.
- **`spi`** interfaces are what other modules implement to plug real persistence / accounts in. `MarketDataProvider` is the same kind of port but lives in `domain` because `Portfolio` itself needs prices.
- **`RebalanceStrategy`** is the open/closed extension point: add a new policy by implementing it, `Portfolio` does not change.

---

## 2. Domain model

```mermaid
classDiagram
    direction TB

    class Portfolio {
        <<aggregate root>>
        -String accountId
        -Map~String,Stock~ stocks
        -TargetAllocation targetAllocation
        +addStock(ticker, quantity, price)
        +sellStock(ticker, quantity)
        +setTargetAllocation(allocation)
        +getTotalValue(prices) BigDecimal
        +getCurrentAllocation(prices) Map
        +rebalance(prices) RebalancePlan
        +applyRebalance(plan)
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
        -Map~String,BigDecimal~ percentages
        +of(percentages)$ TargetAllocation
        +percentageFor(ticker) BigDecimal
        +tickers() Set
    }

    class RebalancePlan {
        <<record>>
        +List~TradeAction~ actions
        +buys() List
        +sells() List
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
        +Set~Stock~ stocks
        +Map targetAllocation
    }

    class RebalanceStrategy {
        <<interface>>
        +plan(holdings, target, prices) RebalancePlan
    }

    class MarketDataProvider {
        <<interface>>
        +getPrice(ticker) BigDecimal
    }

    class ProportionalRebalanceStrategy {
        +plan(holdings, target, prices) RebalancePlan
    }

    Portfolio "1" *-- "0..*" Stock : holds, one per ticker
    Portfolio "1" o-- "0..1" TargetAllocation : aims for
    Portfolio ..> RebalanceStrategy : delegates planning
    Portfolio ..> MarketDataProvider : asks for prices
    Portfolio ..> PortfolioSnapshot : creates
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
| Quantity is a positive whole number; price is positive | `Stock`, `TradeAction` |
| At most one position per ticker (re-buying merges, average price is the weighted average) | `Portfolio.addStock` |
| A position that is fully sold disappears; you cannot sell more than you hold | `Portfolio.sellStock` |
| Target percentages are in (0, 100], tickers are unique and the sum is **exactly 100** | `TargetAllocation.of` |
| Rebalancing needs a target; `rebalance()` never mutates, `applyRebalance()` validates all sells before changing anything | `Portfolio` |
| **One portfolio per account** | `DefaultPortfolioService.createPortfolio` |

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
    Svc->>Repo: findByAccountId("acc1")
    Repo-->>Svc: Portfolio (or PortfolioNotFoundException)

    Svc->>PF: rebalance(marketData)
    alt no target allocation defined
        PF-->>Svc: InvalidAllocationException
    else target defined
        PF->>Strat: plan(stocks, target, marketData)
        loop every ticker held or targeted
            Strat->>Mkt: getPrice(ticker)
            Mkt-->>Strat: price (or PriceUnavailableException)
        end
        Strat-->>PF: RebalancePlan (sells + buys)
        PF-->>Svc: RebalancePlan
    end

    Svc->>PF: applyRebalance(plan)
    Note over PF: validate every sell first,<br/>then sell, then buy
    Svc->>Repo: save(portfolio)
    Svc-->>CLI: RebalancePlan
    CLI-->>User: prints SELL / BUY lines

    Note over CLI,User: any PortfolioException is caught by the CLI<br/>and printed as "Error: …"
```

`rebalance <account>` (without `apply`) calls `PortfolioService.rebalance` instead: it stops after step 11, returns the plan and leaves the portfolio untouched (nothing is applied or saved).

---

## 4. Rebalance algorithm (`ProportionalRebalanceStrategy`)

```mermaid
flowchart TD
    A(["Input: holdings, target, prices"])
    B{"Holdings empty?"}
    Z(["Empty plan - nothing to distribute"])

    subgraph setup["Setup - once"]
        C["Price every ticker held or targeted<br/>total = sum of quantity x price over holdings"]
    end

    subgraph sells["Phase 1 - for each ticker"]
        D["targetValue = total x target% / 100<br/>(ticker not in target: 0%)<br/>gap = targetValue - currentValue"]
        F{"gap"}
        G["SELL round-to-nearest(-gap / price) shares<br/>proceeds += sale value<br/>(0 shares: no order)"]
        H["record as deficit"]
    end

    subgraph buys["Phase 2 - buys, largest deficit first"]
        J["sort deficits by gap, descending"]
        K["shares = min( floor(gap / price), floor(budget / price) )<br/>budget starts at proceeds, minus each buy"]
    end

    L(["Plan = sells + buys<br/>buys never exceed sale proceeds"])

    A --> B
    B -- yes --> Z
    B -- no --> C
    C --> D
    D --> F
    F -- "negative: overweight" --> G
    F -- "positive: underweight" --> H
    G --> J
    H --> J
    J --> K
    K --> L
```

The plan is **self-financing** (no extra cash assumed) and in **whole shares**, so a small residual drift from the exact target is expected.

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
    PortfolioException <|-- PriceUnavailableException
```

| Exception | Raised by | When |
|-----------|-----------|------|
| `AccountNotFoundException` | `DefaultPortfolioService` | `createPortfolio` for an account the `AccountDirectory` does not know |
| `PortfolioAlreadyExistsException` | `DefaultPortfolioService` | `createPortfolio` for an account that already has one |
| `PortfolioNotFoundException` | `DefaultPortfolioService` | any operation on an account without a portfolio |
| `InvalidAllocationException` | `TargetAllocation`, `Portfolio` | percentages malformed or not summing to 100; `rebalance` without a target |
| `InsufficientQuantityException` | `Portfolio` | selling more shares than held (also when applying a plan) |
| `PriceUnavailableException` | `MarketDataProvider` implementations | no price for a ticker needed for valuation or rebalancing |

**Propagation:** the domain throws, `DefaultPortfolioService` logs the failure once at ERROR and rethrows, and the caller decides what to do:
`PortfolioCli` logs a WARN and prints `Error: …`; the main application can handle them as it sees fit.
Malformed input (bad ticker, non-positive quantity or price) is an `IllegalArgumentException`, handled the same way by the CLI.

Logging (SLF4J + Logback → `logs/portfolio.log`): `Portfolio` logs state changes at INFO, the service logs creation and failed operations,
and the CLI logs every command plus recoverable failures (WARN) and unexpected ones (ERROR with stack trace).
