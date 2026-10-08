package org.example.portfolio.infra;

import java.util.Set;

import org.example.portfolio.spi.AccountDirectory;

/** Stand-in for the external account service. */
public class MockAccountDirectory implements AccountDirectory {

    private final Set<String> knownAccounts;

    /** Accepts every non-blank account id. */
    public MockAccountDirectory() {
        this.knownAccounts = null;
    }

    /** Accepts only the given account ids. */
    public MockAccountDirectory(Set<String> knownAccounts) {
        this.knownAccounts = Set.copyOf(knownAccounts);
    }

    @Override
    public boolean exists(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return false;
        }
        return knownAccounts == null || knownAccounts.contains(accountId);
    }
}
