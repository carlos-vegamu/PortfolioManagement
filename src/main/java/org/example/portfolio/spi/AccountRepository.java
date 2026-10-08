package org.example.portfolio.spi;

/** Port to the external account service. Implementations must be safe to call from several threads. */
public interface AccountRepository {

    boolean exists(String accountId);
}
