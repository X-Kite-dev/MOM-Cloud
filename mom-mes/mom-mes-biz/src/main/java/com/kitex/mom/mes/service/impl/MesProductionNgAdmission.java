package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesToolingExecutionEntity;
import com.kitex.mom.mes.enums.MesDeviceGateStageEnum;
import com.kitex.mom.mes.enums.WorksiteTypeEnum;
import com.kitex.mom.mes.mapper.MesToolingExecutionMapper;
import com.kitex.mom.mes.service.MesChangePointHandleService;
import com.kitex.mom.mes.service.MesDeviceGateService;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 新NG人工准入：工站和移动分别核原契约，共用员工/工站/工装/设备/停线门。
 */
@Component
@RequiredArgsConstructor
public class MesProductionNgAdmission {
    private final MesExecutionTemplateService templates;
    private final MesToolingExecutionMapper tooling;
    private final MesDeviceGateService devices;
    private final MesChangePointHandleService changePoints;
    private final MesAndonProductionReleaseService andon;
    private final MesRepairProductionReleaseService repair;

    /**
     * 已持原来源域及派工行锁；mobile由可信入口固定指定，HTTP正文不能自选通道。
     */
    public void manual(MesTaskArrangeEntity arrange, String worksite, String overrideReason, boolean mobile) {
        var contract = templates.contract(arrange);
        require(Boolean.TRUE.equals(mobile ? contract.getMobileAllowed() : contract.getStationAllowed())
                        && contract.getAllowedTriggers() != null && contract.getAllowedTriggers().contains("MANUAL"),
                "原派工契约未开放" + (mobile ? "移动" : "工站") + "人工采集");
        require("NONE".equals(contract.getIdentifierNodePolicy().getAction()),
                "动态换码节点尚无NG前驱实物消费协议，不能绕过原节点采集");
        require(split(arrange.getWorkerCode()).contains(SecurityUtils.getUser().getUsername()),
                "当前认证员工不是该原派工的指定执行人");
        require(worksite != null && !worksite.isBlank() && Objects.equals(worksite, arrange.getActualWorksiteCode()),
                "采集工站须与原派工接收时确认的实际工站相同");
        require(WorksiteTypeEnum.fromCode(arrange.getWorksiteTypes()) != WorksiteTypeEnum.AUTO
                || !overrideReason.isBlank(), "自动工站人工接管必须明确原因");
        require(!Boolean.TRUE.equals(contract.getDeviceRequired())
                        || arrange.getPlannedDeviceCode() != null && !arrange.getPlannedDeviceCode().isBlank(),
                "原契约要求设备，但派工没有冻结实际设备");
        var planned = split(arrange.getPlannedToolingCodes());
        require(!Boolean.TRUE.equals(contract.getToolingRequired()) || !planned.isEmpty(),
                "原契约要求工装，但派工没有冻结计划工装");
        if (!planned.isEmpty()) {
            var inUse = tooling.selectList(new LambdaQueryWrapper<MesToolingExecutionEntity>()
                            .eq(MesToolingExecutionEntity::getTenantId, arrange.getTenantId())
                            .eq(MesToolingExecutionEntity::getFactoryCode, arrange.getFactoryCode())
                            .eq(MesToolingExecutionEntity::getArrangeCode, arrange.getCode())
                            .in(MesToolingExecutionEntity::getToolingCode, planned)).stream()
                    .filter(row -> "IN_USE".equals(row.getStatus()))
                    .map(MesToolingExecutionEntity::getToolingCode).collect(Collectors.toSet());
            require(planned.equals(inUse), "原派工冻结工装没有全部在用，不能登记新生产实物");
        }
        changePoints.assertReportAllowed(arrange);
        andon.assertAllowed(arrange, "人工不良实物采集");
        repair.assertAllowed(arrange, "人工不良实物采集");
        var decision = devices.check(MesDeviceGateStageEnum.REPORT, arrange, arrange.getPlannedDeviceCode(), worksite);
        require(decision != null && Boolean.TRUE.equals(decision.getAllowed()),
                "设备运行门阻断采集：" + (decision == null ? "未取得实际设备结论" : decision.getMessage()));
    }

    private Set<String> split(String codes) {
        return codes == null ? Set.of() : Arrays.stream(codes.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toSet());
    }
}
