package com.tang.plugin.config;

import org.springframework.lang.NonNull;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * No-op transaction manager for skeleton boot without DataSource.
 */
public class NoOpPlatformTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    @NonNull
    protected Object doGetTransaction() throws TransactionException {
        return new Object();
    }

    @Override
    protected void doBegin(@NonNull Object transaction, @NonNull TransactionDefinition definition)
            throws TransactionException {
        // no-op
    }

    @Override
    protected void doCommit(@NonNull DefaultTransactionStatus status) throws TransactionException {
        // no-op
    }

    @Override
    protected void doRollback(@NonNull DefaultTransactionStatus status) throws TransactionException {
        // no-op
    }
}
