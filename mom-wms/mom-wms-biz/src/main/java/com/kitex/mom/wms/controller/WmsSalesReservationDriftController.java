package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsSalesReservationDriftDTO;
import com.kitex.mom.wms.entity.WmsSalesReservationDriftCaseEntity;
import com.kitex.mom.wms.entity.WmsSalesReservationDriftOperationEntity;
import com.kitex.mom.wms.service.WmsSalesReservationDriftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsSalesReservationDrift")
@Tag(name = "销售预留漂移待办与换批审批")
public class WmsSalesReservationDriftController {
    private final WmsSalesReservationDriftService service;

    @GetMapping("/page")
    @Operation(summary = "销售预留漂移待办分页")
    @PreAuthorize("@pms.hasPermission('wms_salesReservationDrift_view')")
    public R<Page<WmsSalesReservationDriftCaseEntity>> page(@ParameterObject SearchPage page,
                                                            @ParameterObject WmsSalesReservationDriftDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "销售预留漂移完整冻结证据")
    @PreAuthorize("@pms.hasPermission('wms_salesReservationDrift_view')")
    public R<WmsSalesReservationDriftCaseEntity> detail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/overview")
    @Operation(summary = "销售预留漂移待办统计")
    @PreAuthorize("@pms.hasPermission('wms_salesReservationDrift_view')")
    public R<Map<String, Long>> overview() {
        return R.ok(service.overview());
    }

    @PostMapping("/scan")
    @Operation(summary = "以当前实物和冻结事实刷新漂移待办")
    @SysLog("扫描销售预留漂移")
    @PreAuthorize("@pms.hasPermission('wms_salesReservationDrift_scan')")
    public R<Map<String, Integer>> scan(@RequestBody WmsSalesReservationDriftDTO.Scan command) {
        return R.ok(service.scan(command));
    }

    @PostMapping("/{caseId}/submit")
    @Operation(summary = "提交全部漂移受控换批方案")
    @SysLog("销售预留漂移换批提审")
    @PreAuthorize("@pms.hasPermission('wms_salesReservationDrift_submit')")
    public R<WmsSalesReservationDriftCaseEntity> submit(@PathVariable Long caseId,
                                                        @RequestBody WmsSalesReservationDriftDTO.Submit command) {
        return R.ok(service.submit(caseId, command));
    }

    @PostMapping("/{caseId}/review")
    @Operation(summary = "异岗审批换批、驳回或关闭已恢复待办")
    @SysLog("销售预留漂移异岗复核")
    @PreAuthorize("@pms.hasPermission('wms_salesReservationDrift_review')")
    public R<WmsSalesReservationDriftCaseEntity> review(@PathVariable Long caseId,
                                                        @RequestBody WmsSalesReservationDriftDTO.Review command) {
        return R.ok(service.review(caseId, command));
    }

    @GetMapping("/{caseId}/operations")
    @Operation(summary = "销售预留漂移不可变操作履历")
    @PreAuthorize("@pms.hasPermission('wms_salesReservationDrift_view')")
    public R<List<WmsSalesReservationDriftOperationEntity>> operations(@PathVariable Long caseId) {
        return R.ok(service.operations(caseId));
    }
}
