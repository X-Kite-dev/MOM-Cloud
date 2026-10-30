package com.kitex.mom.plan.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.plan.mapper.PlnFactoryPlanningImpactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * PLAN只返回当前实际租户/工厂的本域影响证据，查询失败由MDM按不可用处理。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/factoryPlanningImpact/internal")
public class PlnFactoryPlanningImpactInternalController {

    private final PlnFactoryPlanningImpactMapper impactMapper;

    @Inner
    @GetMapping("/current")
    public R<MdmFactoryPlanningImpactDTO.ModuleImpact> current(@RequestParam String factoryCode) {
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId <= 0 || TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || factoryCode == null || factoryCode.isBlank() || !Objects.equals(factoryCode, FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("影响检查必须在实际租户及当前工厂上下文执行");
        }
        boolean cases = impactMapper.apsCaseInstalled();
        boolean dispatch = impactMapper.apsDispatchInstalled();
        if (cases != dispatch)
            throw new CheckedException("智能APS结构不完整，请先完成模块升级");
        var result = impactMapper.current(tenantId, factoryCode);
        if (cases) {
            var aps = impactMapper.intelligentAps(tenantId, factoryCode);
            result.setFormalSourceCount(Math.addExact(result.getFormalSourceCount(), aps.getFormalSourceCount()));
            result.setLockedApsCount(Math.addExact(result.getLockedApsCount(), aps.getLockedApsCount()));
            result.setPendingErpEventCount(Math.addExact(result.getPendingErpEventCount(), aps.getPendingErpEventCount()));
        }
        result.setUnfinishedTaskCount(0L).setIntelligentApsInstalled(cases);
        return R.ok(result.setTenantId(tenantId).setModule("PLAN").setFactoryCode(factoryCode).setAvailable(true).setMessage("PLAN实时影响证据已采集"));
    }
}
