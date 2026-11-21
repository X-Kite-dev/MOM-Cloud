package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesDispatchedOrderProgressDTO;
import com.kitex.mom.mes.dto.MesPlanExecutionEvidenceDTO;
import com.kitex.mom.mes.service.MesPlanExecutionEvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计划业务模块受保护的MES批量只读契约。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesPlanExecution")
public class MesPlanExecutionEvidenceController {
    private final MesPlanExecutionEvidenceService service;

    /** 计划员凭原回执读取单张工单，服务层另行核对当前租户与工厂权限。 */
    @PostMapping("/order-progress")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "按原下达回执查询 MES 工单执行情况")
    public R<MesDispatchedOrderProgressDTO.View> orderProgress(@RequestBody MesDispatchedOrderProgressDTO.Query query) {
        return R.ok(service.orderProgress(query));
    }

    @Inner
    @PostMapping("/internal/query")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "来源计划直接/APS分配数量与工序执行证据")
    public R<MesPlanExecutionEvidenceDTO.Evidence> query(@RequestBody MesPlanExecutionEvidenceDTO.Query query) {
        return R.ok(service.query(query));
    }
}
