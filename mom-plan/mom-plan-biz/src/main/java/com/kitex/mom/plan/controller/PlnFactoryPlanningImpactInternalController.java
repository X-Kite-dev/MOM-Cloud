package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnApsCaseEntity;
import com.kitex.mom.plan.entity.PlnApsMesDispatchOutboxEntity;
import com.kitex.mom.plan.mapper.PlanMatMrpMapper;
import com.kitex.mom.plan.mapper.PlnApsCaseMapper;
import com.kitex.mom.plan.mapper.PlnApsMesDispatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/** PLAN 向 MDM 提供当前工厂权威切换影响，只查询本模块业务表。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/factoryPlanningImpact/internal")
public class PlnFactoryPlanningImpactInternalController {
    private final PlanMatMrpMapper mrpMapper;
    private final PlnApsCaseMapper apsCaseMapper;
    private final PlnApsMesDispatchOutboxMapper dispatchOutboxMapper;

    @Inner
    @GetMapping("/current")
    public R<MdmFactoryPlanningImpactDTO.ModuleImpact> current(@RequestParam String factoryCode) {
        requireCurrentFactory(factoryCode);
        long activeMrp = mrpMapper.selectCount(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getFactoryCode, factoryCode)
                .eq(PlanMatMrpEntity::getDelFlag, "0")
                .eq(PlanMatMrpEntity::getStatus, false));
        long lockedAps = apsCaseMapper.selectCount(new LambdaQueryWrapper<PlnApsCaseEntity>()
                .eq(PlnApsCaseEntity::getFactoryCode, factoryCode)
                .eq(PlnApsCaseEntity::getDelFlag, "0")
                .notIn(PlnApsCaseEntity::getCaseStatus, Set.of("DRAFT", "COMPLETED", "CANCELLED", "SUPERSEDED")));
        long pendingDispatch = dispatchOutboxMapper.selectCount(
                new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                        .eq(PlnApsMesDispatchOutboxEntity::getFactoryCode, factoryCode)
                        .eq(PlnApsMesDispatchOutboxEntity::getDelFlag, "0")
                        .notIn(PlnApsMesDispatchOutboxEntity::getStatus,
                                Set.of("SUCCEEDED", "CANCELLED", "DEAD")));
        return R.ok(new MdmFactoryPlanningImpactDTO.ModuleImpact()
                .setModule("PLAN")
                .setFactoryCode(factoryCode)
                .setAvailable(true)
                .setActiveMrpCount(activeMrp)
                .setLockedApsCount(lockedAps)
                .setPendingErpEventCount(pendingDispatch)
                .setUnfinishedTaskCount(0L)
                .setMessage("PLAN实时影响证据已采集"));
    }

    private static void requireCurrentFactory(String factoryCode) {
        if (factoryCode == null || factoryCode.isBlank()
                || !Objects.equals(factoryCode.trim(), FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("PLAN影响检查工厂与当前工厂上下文不一致");
        }
    }
}
