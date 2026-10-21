package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesReportReverseRequestDTO;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import com.kitex.mom.mes.service.MesReportReverseRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 报工冲销的申请、异岗复核、撤回和永久履历入口。
 */
@Tag(description = "mesReportReverseRequest", name = "报工受控冲销")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/report-reverse")
public class MesReportReverseRequestController {

    private final MesReportReverseRequestService service;

    @Operation(summary = "冲销申请分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:view')")
    public R<IPage<MesReportReverseRequestEntity>> page(Page<MesReportReverseRequestEntity> page,
                                                        MesReportReverseRequestEntity query) {
        return R.ok(service.pageRequests(page, query));
    }

    @Operation(summary = "重采原报工、库存、投料、包装和质量事实")
    @GetMapping("/preview/{reportId}")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:apply')")
    public R<MesReportReverseRequestDTO.Evidence> preview(
            @PathVariable Long reportId,
            @RequestParam(value = "correctionMode", required = false) String correctionMode) {
        return R.ok(service.preview(reportId, correctionMode));
    }

    @Operation(summary = "查看冲销申请、当前事实和永久履历")
    @GetMapping("/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:view')")
    public R<MesReportReverseRequestDTO.Detail> detail(@PathVariable Long requestId) {
        return R.ok(service.detail(requestId));
    }

    @Operation(summary = "提交报工冲销申请")
    @SysLog("提交报工冲销申请")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:apply')")
    public R<MesReportReverseRequestEntity> submit(@RequestBody MesReportReverseRequestDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @Operation(summary = "异岗批准或驳回冲销申请")
    @SysLog("审批报工冲销申请")
    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:review')")
    public R<MesReportReverseRequestEntity> review(@RequestBody MesReportReverseRequestDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @Operation(summary = "原申请人撤回待审冲销申请")
    @SysLog("撤回报工冲销申请")
    @PostMapping("/withdraw")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:apply')")
    public R<MesReportReverseRequestEntity> withdraw(@RequestBody MesReportReverseRequestDTO.WithdrawCommand command) {
        return R.ok(service.withdraw(command));
    }
}
