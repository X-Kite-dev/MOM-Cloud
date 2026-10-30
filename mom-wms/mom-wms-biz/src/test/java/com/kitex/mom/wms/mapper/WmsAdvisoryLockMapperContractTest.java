package com.kitex.mom.wms.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WmsAdvisoryLockMapperContractTest {

    @Test
    void transactionLocksReturnAnIntegerResultToMyBatis() throws NoSuchMethodException {
        List<LockMethod> locks = List.of(
                new LockMethod(WmsWarehouseMenuMapper.class, "lockConfiguration"),
                new LockMethod(WmsAllocationBillMapper.class, "lockAgentTransfer"),
                new LockMethod(WmsDeliverGoodsPlanBaseMapper.class, "lockOmsSource"),
                new LockMethod(WmsInNoticeMapper.class, "lockSourceRequest"),
                new LockMethod(WmsOmsDeliveryIssueMapper.class, "lockKey"),
                new LockMethod(WmsOmsDeliveryWithdrawalMapper.class, "lockKey"),
                new LockMethod(WmsPlanShortageEventOutboxMapper.class, "lockKey"),
                new LockMethod(WmsSalesReservationDriftCaseMapper.class, "lockKey"),
                new LockMethod(WmsShipmentExceptionResolutionMapper.class, "lockKey"),
                new LockMethod(WmsTransferRoutePolicyMapper.class, "lockPolicyDimension"),
                new LockMethod(WmsTransferVariancePolicyMapper.class, "lockPolicyDimension"),
                new LockMethod(WmsInsideInventoryPlanMapper.class, "lockCountScope"));

        for (LockMethod lock : locks) {
            Method method = lock.mapper().getMethod(lock.methodName(), String.class);
            Select select = method.getAnnotation(Select.class);
            assertNotNull(select, method.toString());
            assertEquals(int.class, method.getReturnType(), method.toString());
            assertEquals(1, select.value().length, method.toString());
            assertTrue(select.value()[0].startsWith("SELECT 1 AS locked FROM pg_advisory_xact_lock("),
                    method.toString());
        }
    }

    @Test
    void countPlanNumberQueryKeepsTenantFactoryAndDeletionScope() throws NoSuchMethodException {
        Method method = WmsInsideInventoryPlanMapper.class.getMethod(
                "countActivePlanNo", Long.class, String.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertTrue(sql.contains("tenant_id = #{tenant}"));
        assertTrue(sql.contains("factory_code = #{factory}"));
        assertTrue(sql.contains("plan_no = #{planNo}"));
        assertTrue(sql.contains("del_flag = '0'"));
    }

    private record LockMethod(Class<?> mapper, String methodName) {
    }
}
