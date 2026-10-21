package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.mes.service.MesPlanExecutionEvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 计划业务模块受保护的MES批量只读契约。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPlanExecution")
public class MesPlanExecutionEvidenceController {
    private final MesPlanExecutionEvidenceService service;

    @Inner
    @PostMapping("/internal/query")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "来源计划直接/APS分配数量与工序执行证据")
    public R<MesPlanExecutionEvidenceDTO.Evidence> query(@RequestBody MesPlanExecutionEvidenceDTO.Query query) {
        return R.ok(service.query(query));
    }
}
