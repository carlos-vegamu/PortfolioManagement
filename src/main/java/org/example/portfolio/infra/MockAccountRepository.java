package org.example.portfolio.infra;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.example.portfolio.spi.AccountRepository;

/** Stand-in for the external account service. Safe to share between threads if its predicate is. */
public class MockAccountRepository implements AccountRepository {

    private final Predicate<String> known;

    /** Accepts every non-blank account id. */
    public MockAccountRepository() {
        this(accountId -> true);
    }

    /** Accepts only the given account ids. */
    public MockAccountRepository(Set<String> knownAccounts) {
        this(Set.copyOf(knownAccounts)::contains);
    }

    /** Accepts the non-blank account ids that match {@code known}. */
    public MockAccountRepository(Predicate<String> known) {
        this.known = Objects.requireNonNull(known, "known");
    }

    @Override
    public boolean exists(String accountId) {
        return accountId != null && !accountId.isBlank() && known.test(accountId);
    }
}
