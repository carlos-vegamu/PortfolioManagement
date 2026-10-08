package org.example;

import java.io.IOException;

import org.example.portfolio.api.PortfolioService;
import org.example.portfolio.cli.PortfolioCli;
import org.example.portfolio.infra.InMemoryPortfolioRepository;
import org.example.portfolio.infra.MockAccountDirectory;
import org.example.portfolio.infra.MockMarketDataProvider;
import org.example.portfolio.service.DefaultPortfolioService;
import org.example.portfolio.service.ProportionalRebalanceStrategy;

/** Composition root: wires the module with in-memory storage and mocked external services. */
public class Main {

    public static void main(String[] args) throws IOException {
        PortfolioService service = new DefaultPortfolioService(
                new InMemoryPortfolioRepository(),
                new MockAccountDirectory(),
                MockMarketDataProvider.withDefaultPrices(),
                new ProportionalRebalanceStrategy());
        new PortfolioCli(service, System.in, System.out).run();
    }
}
