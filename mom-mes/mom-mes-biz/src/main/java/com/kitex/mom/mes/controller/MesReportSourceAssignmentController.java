package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesReportSourceAssignmentDTO;
import com.kitex.mom.mes.entity.MesReportSourceAssignmentEntity;
import com.kitex.mom.mes.exception.MesReportSourceRejectedException;
import com.kitex.mom.mes.service.MesReportSourceAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Web/PDA共用来源核定入口。只允许明确业务命令，不暴露通用增删改。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/report-source")
@Tag(name = "末序报工来源核定")
public class MesReportSourceAssignmentController {
    private final MesReportSourceAssignmentService service;

    @GetMapping("/preview/{reportId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "报工来源全量核对预览")
    public R<MesReportSourceAssignmentDTO.Preview> preview(@PathVariable("reportId") Long reportId) {
        return R.ok(service.preview(reportId));
    }

    @PostMapping("/assign")
    @PreAuthorize("@pms.hasPermission('mesTask:edit')")
    @Operation(summary = "按真实凭证追加末序来源核定版本")
    public R<MesReportSourceAssignmentEntity> assign(@RequestBody MesReportSourceAssignmentDTO.Assign command) {
        return R.ok(service.assign(command));
    }

    /**
     * 仅在事务代理完成回滚后响应；绝不以通用code=1推断原号未提交。
     */
    @ExceptionHandler(MesReportSourceRejectedException.class)
    public R<MesReportSourceAssignmentDTO.Rejection> rejected(MesReportSourceRejectedException exception) {
        return R.ok(new MesReportSourceAssignmentDTO.Rejection().setOutcome("ROLLBACK_CONFIRMED")
                .setRequestId(exception.getRequestId()).setReportId(exception.getReportId())
                .setExpectedFingerprint(exception.getExpectedFingerprint()).setMessage(exception.getMessage()));
    }

    @GetMapping("/history/{reportId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "原报工不可变核定历史")
    public R<List<MesReportSourceAssignmentEntity>> history(@PathVariable("reportId") Long reportId) {
        return R.ok(service.history(reportId));
    }

    @GetMapping("/request/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "原请求核对，未观察到不等于取消")
    public R<MesReportSourceAssignmentEntity> request(@PathVariable("requestId") String requestId) {
        return R.ok(service.byRequest(requestId));
    }
}
