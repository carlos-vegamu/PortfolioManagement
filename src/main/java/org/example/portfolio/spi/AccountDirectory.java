package org.example.portfolio.spi;

/** Port to the external account service. */
public interface AccountDirectory {

    boolean exists(String accountId);
}
