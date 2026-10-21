package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/** MES 向 MDM 提供当前工厂未结执行影响，只查询 MES 本模块业务表。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/factoryPlanningImpact/internal")
public class MesFactoryPlanningImpactInternalController {
    private final MesTaskMapper taskMapper;

    @Inner
    @GetMapping("/current")
    public R<MdmFactoryPlanningImpactDTO.ModuleImpact> current(@RequestParam String factoryCode) {
        if (factoryCode == null || factoryCode.isBlank()
                || !Objects.equals(factoryCode.trim(), FactoryContextHolder.getFactoryCode())) {
            throw new CheckedException("MES影响检查工厂与当前工厂上下文不一致");
        }
        long unfinished = taskMapper.selectCount(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factoryCode)
                .eq(MesTaskEntity::getDelFlag, "0")
                .notIn(MesTaskEntity::getStatus, Set.of("FINISH", "CLOSE")));
        return R.ok(new MdmFactoryPlanningImpactDTO.ModuleImpact()
                .setModule("MES")
                .setFactoryCode(factoryCode)
                .setAvailable(true)
                .setActiveMrpCount(0L)
                .setLockedApsCount(0L)
                .setPendingErpEventCount(0L)
                .setUnfinishedTaskCount(unfinished)
                .setMessage("MES实时影响证据已采集"));
    }
}
