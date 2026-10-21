package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesReportCorrectionSettlementDTO;
import com.kitex.mom.mes.entity.MesReportCorrectionSettlementEntity;
import com.kitex.mom.mes.service.MesReportCorrectionSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 聚合报工部分纠正后的差异结案、异岗复核与永久履历入口。 */
@Tag(description = "mesReportCorrectionSettlement", name = "聚合报工差异结案")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/report-correction-settlement")
public class MesReportCorrectionSettlementController {

    private final MesReportCorrectionSettlementService service;

    @Operation(summary = "差异结案分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:view')")
    public R<IPage<MesReportCorrectionSettlementEntity>> page(
            Page<MesReportCorrectionSettlementEntity> page, MesReportCorrectionSettlementEntity query) {
        return R.ok(service.pageSettlements(page, query));
    }

    @Operation(summary = "重采纠正数量、受控重报和许可状态")
    @GetMapping("/preview/{reverseRequestId}")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:apply')")
    public R<MesReportCorrectionSettlementDTO.Evidence> preview(@PathVariable Long reverseRequestId) {
        return R.ok(service.preview(reverseRequestId));
    }

    @Operation(summary = "查看结案单、当前事实和永久履历")
    @GetMapping("/{settlementId}")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:view')")
    public R<MesReportCorrectionSettlementDTO.Detail> detail(@PathVariable Long settlementId) {
        return R.ok(service.detail(settlementId));
    }

    @Operation(summary = "提交聚合报工差异结案申请")
    @SysLog("提交聚合报工差异结案申请")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:apply')")
    public R<MesReportCorrectionSettlementEntity> submit(
            @RequestBody MesReportCorrectionSettlementDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @Operation(summary = "异岗批准或驳回差异结案申请")
    @SysLog("复核聚合报工差异结案")
    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:review')")
    public R<MesReportCorrectionSettlementEntity> review(
            @RequestBody MesReportCorrectionSettlementDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @Operation(summary = "原申请人撤回待审差异结案申请")
    @SysLog("撤回聚合报工差异结案申请")
    @PostMapping("/withdraw")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:apply')")
    public R<MesReportCorrectionSettlementEntity> withdraw(
            @RequestBody MesReportCorrectionSettlementDTO.WithdrawCommand command) {
        return R.ok(service.withdraw(command));
    }
}
