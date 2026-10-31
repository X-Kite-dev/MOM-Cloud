package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;

import java.util.Arrays;
import java.util.Objects;

/**
 * 报工共同父状态规则；实际行及工单锁由原报工事务持有，不另行授权退回。
 */
final class MesReportParentStateGuard {
    private MesReportParentStateGuard() {
    }

    /**
     * approvedReturn只能来自原批准退回协调器的核定结果，不是客户端字段。
     */
    static void validate(
            MesTaskArrangeEntity arrange,
            MesTaskEntity task,
            MesProduceTaskEntity process,
            boolean approvedReturn) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(
                tenant != null
                        && tenant > 0
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && factory != null
                        && !factory.isBlank()
                        && arrange != null
                        && task != null
                        && process != null
                        && arrange.getId() != null
                        && task.getId() != null
                        && process.getId() != null
                        && Objects.equals(tenant, arrange.getTenantId())
                        && Objects.equals(tenant, task.getTenantId())
                        && Objects.equals(factory, arrange.getFactoryCode())
                        && Objects.equals(factory, task.getFactoryCode())
                        && Objects.equals(arrange.getTaskCode(), task.getCode()),
                "报工父对象缺少同一实际租户、工厂或工单依据");
        MesFrozenExecutionGraph.assertArrangement(process, arrange);
        require(
                Arrays.stream(MesTaskStatusEnum.values())
                        .anyMatch(s -> s.name().equals(task.getStatus()))
                        && Arrays.stream(MesProduceTaskStatusEnum.values())
                        .anyMatch(s -> s.name().equals(process.getStatus())),
                "报工父工单或工序状态缺失或未知");
        require(
                !"CLOSE".equals(task.getStatus()) && !"CLOSE".equals(process.getStatus()),
                "父工单或工序已经关闭，不能继续报工");
        require(
                approvedReturn
                        || !"FINISH".equals(task.getStatus())
                        && !"FINISH".equals(process.getStatus()),
                "父工单或工序已经完工，新报工须先受控重新打开");
    }

    private static void require(boolean allowed, String message) {
        if (!allowed) throw new CheckedException(message);
    }
}
