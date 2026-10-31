package com.kitex.mom.wms.service.impl;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.function.Supplier;

import static com.kitex.mom.wms.service.impl.WmsInventoryPlanAccess.require;

/**
 * Only the locked inventory command may create inventory adjustment bills. Never propagated to worker threads.
 */
public final class WmsInventoryAdjustmentContext {
    public static final String DOCUMENT = "wms_inventory_detail";
    private static final ThreadLocal<Long> DETAIL = new ThreadLocal<>();

    private WmsInventoryAdjustmentContext() {
    }

    static <T> T execute(Long id, Supplier<T> action) {
        require(DETAIL.get() == null && TransactionSynchronizationManager.isActualTransactionActive(),
                "盘点损益必须在统一业务事务内办理");
        DETAIL.set(id);
        try {
            return action.get();
        } finally {
            DETAIL.remove();
        }
    }

    public static void check(String billType, Long documentId, String documentTable) {
        if (!"other_inventory".equals(billType) && !"inventory".equals(billType)) {
            return;
        }
        require(DOCUMENT.equals(documentTable) && documentId != null && Objects.equals(DETAIL.get(), documentId)
                        && TransactionSynchronizationManager.isActualTransactionActive(),
                "盘点损益请从盘点任务办理；旧版分步出入库入口已关闭，请刷新或升级客户端");
    }
}
