package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.service.MesQualityGateService;
import com.kitex.mom.mes.service.MesQualityQuantityService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesQualityGate")
@Tag(name = "MES工序质量门")
public class MesQualityGateController {

    private final MesQualityGateService qualityGateService;
    private final MesQualityQuantityService qualityQuantityService;
    private final MesActualFactoryAccessService factoryAccess;

    @PostMapping("/sync")
    @Operation(summary = "同步并查询工序质量门状态")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:show')")
    public R<MesQualityGateDecision> sync(@RequestParam String produceTaskCode) {
        factoryAccess.require("write");
        MesQualityGateDecision decision = qualityGateService.sync(produceTaskCode);
        return R.ok(qualityQuantityService.reconcile(produceTaskCode, decision));
    }

    @PostMapping("/retry/{recordId}")
    @Operation(summary = "重试生成失败的工序质检任务")
    @PreAuthorize("@pms.hasPermission('mesInspectionGenRecord:retry')")
    public R<MesQualityGateDecision> retry(@PathVariable Long recordId) {
        factoryAccess.require("write");
        MesQualityGateDecision decision = qualityGateService.retryGeneration(recordId);
        String produceTaskCode = decision.getItems().stream().findFirst()
                .map(item -> item.getProduceTaskCode()).orElse(null);
        return R.ok(produceTaskCode == null ? decision
                : qualityQuantityService.reconcile(produceTaskCode, decision));
    }
}
