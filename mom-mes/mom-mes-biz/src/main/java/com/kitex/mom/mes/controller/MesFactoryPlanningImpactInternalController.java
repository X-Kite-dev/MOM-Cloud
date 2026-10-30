package com.kitex.mom.mes.controller;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mes.mapper.MesFactoryPlanningImpactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * MES只返回当前实际租户/工厂的本域影响证据，查询失败由MDM按不可用处理。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/factoryPlanningImpact/internal")
public class MesFactoryPlanningImpactInternalController {

    private final MesFactoryPlanningImpactMapper impactMapper;

    @Inner
    @GetMapping("/current")
    public R<MdmFactoryPlanningImpactDTO.ModuleImpact> current(@RequestParam String factoryCode) {
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId <= 0 || TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || factoryCode == null || factoryCode.isBlank() || !Objects.equals(factoryCode, FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("影响检查必须在实际租户及当前工厂上下文执行");
        }
        var result = impactMapper.current(tenantId, factoryCode);
        result.setActiveMrpCount(0L).setLockedApsCount(0L).setIntelligentApsInstalled(false);
        return R.ok(result.setTenantId(tenantId).setModule("MES").setFactoryCode(factoryCode).setAvailable(true).setMessage("MES实时影响证据已采集"));
    }
}
