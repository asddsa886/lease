package com.atguigu.lease.common.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务工具：在事务提交后执行回调。
 * <p>
 * 用于缓存一致性：DB 更新成功提交后再删缓存，避免回滚导致缓存被误删。
 */
public final class TransactionUtils {

    private TransactionUtils() {
    }

    public static void runAfterCommit(Runnable runnable) {
        if (runnable == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            runnable.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }
}
