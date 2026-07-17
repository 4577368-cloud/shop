package com.tang.plugin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import java.util.function.Supplier;

/**
 * Narrow programmatic transaction helper (docs: txManger.run).
 * Skeleton: executes without real DataSource until DB is wired.
 */
@Slf4j
@Component
public class TxManger {

    @Resource
    private TransactionTemplate autoSwitchTransactionTemplate;

    public void run(Runnable action) {
        autoSwitchTransactionTemplate.executeWithoutResult(status -> action.run());
    }

    public <T> T run(Supplier<T> action) {
        return autoSwitchTransactionTemplate.execute(status -> action.get());
    }
}
