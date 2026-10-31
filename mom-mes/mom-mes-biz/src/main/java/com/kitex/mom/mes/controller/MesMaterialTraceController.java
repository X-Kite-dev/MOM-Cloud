package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO;
import com.kitex.mom.mes.dto.MesMaterialSourceDTO;
import com.kitex.mom.mes.dto.MesMaterialDispositionDTO;
import com.kitex.mom.mes.service.impl.MesMaterialDispositionReader;
import com.kitex.mom.mes.service.impl.MesMaterialConsumptionTraceService;
import com.kitex.mom.mes.service.impl.MesMaterialSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 生产物料来源及实际耗用下钻，不提供重新扣料或改写原账动作。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesMaterialTrace")
@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
public class MesMaterialTraceController {
    private final MesMaterialSourceService sources;
    private final MesMaterialConsumptionTraceService consumptions;
    private final MesMaterialDispositionReader dispositions;

    @GetMapping("/source")
    public R<MesMaterialSourceDTO.Result> source(MesMaterialSourceDTO.Query query) {
        return R.ok(sources.inspect(query));
    }

    @GetMapping("/consumptions")
    public R<MesMaterialConsumptionTraceDTO.Page> consumptions(MesMaterialConsumptionTraceDTO.Query query) {
        return R.ok(consumptions.page(query));
    }

    @GetMapping("/consumptions/{sourceKind}/{sourceId}")
    public R<MesMaterialConsumptionTraceDTO.Detail> consumption(@PathVariable String sourceKind,
                                                                @PathVariable String sourceId, @RequestParam String taskCode,
                                                                @RequestParam(required = false) String arrangeCode) {
        return R.ok(consumptions.detail(sourceKind, sourceId, taskCode, arrangeCode));
    }
    /** 精确原耗料关联的同预留物料差异，不执行跨模块扫描。 */
    @GetMapping("/consumptions/{sourceKind}/{sourceId}/reconciliation")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') && @pms.hasPermission('mesMaterialReconcileIssue:show')")
    public R<MesMaterialDispositionDTO.Result> reconciliation(@PathVariable String sourceKind,
            @PathVariable String sourceId, MesMaterialDispositionDTO.Query query) {
        return R.ok(dispositions.reconciliation(sourceKind, sourceId, query));
    }

    /** 原质量来源预览权限；仅发现产出NG的已有案件，不自动登记NCR。 */
    @GetMapping("/consumptions/{sourceKind}/{sourceId}/output-quality")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show') && @pms.hasPermission('bad:add')")
    public R<MesMaterialDispositionDTO.Result> outputQuality(@PathVariable String sourceKind,
            @PathVariable String sourceId, MesMaterialDispositionDTO.Query query) {
        return R.ok(dispositions.outputQuality(sourceKind, sourceId, query));
    }

}
