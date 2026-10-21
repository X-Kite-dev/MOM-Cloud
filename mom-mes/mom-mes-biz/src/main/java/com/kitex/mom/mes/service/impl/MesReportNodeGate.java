package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.util.MesPredecessorPassEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 新报工的实际节点与逐标识前序门。调用者负责持有原工单/派工事务锁；成功原号先恢复而不重判。
 * 不是持久实体CRUD服务，不管理过站、库存、数量或批准退回的生命周期。
 */
@Component
@RequiredArgsConstructor
public class MesReportNodeGate {
    private final MesExecutionNodeReader nodes;
    private final MesTaskReportMapper reports;

    public MesExecutionNodeReader.NodeContext validate(MesTaskArrangeEntity arrange, ReportModeEnum mode,
                                                       String identifier, boolean approvedReturn) {
        var context = nodes.read(arrange);
        if (mode != ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType())) {
            throw new CheckedException("报工对象模式与原实际派工不一致");
        }
        // 退回下一站/轮次已由原批准协调器办理，不能用普通首轮历史前序替它放行。
        if (approvedReturn || !Set.of(ReportModeEnum.ONE_CODE, ReportModeEnum.PACKAGE_ONE_CODE,
                ReportModeEnum.TRANSFER_CARD).contains(mode)) {
            return context;
        }
        if (identifier == null || identifier.isBlank()) {
            throw new CheckedException("逐标识报工缺少规范生产标识");
        }
        if (context.predecessors().isEmpty()) {
            return context;
        }
        var actual = reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, arrange.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, arrange.getFactoryCode())
                .eq(MesTaskReportEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesTaskReportEntity::getTransferCode, identifier)
                .in(MesTaskReportEntity::getProduceTaskCode, context.predecessors().stream()
                        .map(MesProduceTaskEntity::getCode).toList())
                .isNull(MesTaskReportEntity::getRouteReturnId));
        var missing = MesPredecessorPassEvidence.missing(context.predecessors(), actual, identifier);
        if (!missing.isEmpty()) {
            throw new CheckedException("标识尚未通过全部实际前序节点：" + missing.stream()
                    .map(row -> (row.getSourceRouteNodeCode() == null ? "旧工序" : row.getSourceRouteNodeCode())
                            + "/" + row.getRouteCode() + "[" + row.getCode() + "]")
                    .collect(Collectors.joining("、")));
        }
        return context;
    }
}
