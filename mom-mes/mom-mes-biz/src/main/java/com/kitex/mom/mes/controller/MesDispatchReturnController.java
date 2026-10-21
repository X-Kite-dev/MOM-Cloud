package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesDispatchReturnDTO;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import com.kitex.mom.mes.service.MesDispatchReturnService;
import com.kitex.mom.mes.service.impl.MesStartPreparationDrilldownService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 已接收未开工派工的申请、异岗审批和永久履历入口。
 */
@Tag(description = "mesDispatchReturn", name = "受控退派")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/dispatch-return")
public class MesDispatchReturnController {

    private final MesDispatchReturnService service;
    private final MesStartPreparationDrilldownService preparationDrilldown;

    @Operation(summary = "退派申请分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesDispatchReturn:view')")
    public R<IPage<MesDispatchReturnRequestEntity>> page(Page<MesDispatchReturnRequestEntity> page,
                                                         MesDispatchReturnRequestEntity query,
                                                         @RequestParam(required = false) Long preparationArrangeId) {
        if (preparationArrangeId != null) {
            var arrange = preparationDrilldown.require(preparationArrangeId, query.getArrangeCode(), query.getTaskCode());
            query.setArrangeId(arrange.getId());
        }
        return R.ok(service.pageRequests(page, query));
    }

    @Operation(summary = "预览已接收未开工派工的退派资格")
    @GetMapping("/preview/{arrangeId}")
    @PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
    public R<MesDispatchReturnDTO.Evidence> preview(@PathVariable Long arrangeId) {
        return R.ok(service.preview(arrangeId));
    }

    @Operation(summary = "查看退派申请、当前事实和永久履历")
    @GetMapping("/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesDispatchReturn:view')")
    public R<MesDispatchReturnDTO.Detail> detail(@PathVariable Long requestId) {
        return R.ok(service.detail(requestId));
    }

    @Operation(summary = "提交受控退派申请")
    @SysLog("提交已接收未开工退派申请")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
    public R<MesDispatchReturnRequestEntity> submit(@RequestBody MesDispatchReturnDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @Operation(summary = "异岗批准或驳回退派申请")
    @SysLog("审批已接收未开工退派申请")
    @PostMapping("/review")
    @PreAuthorize("@pms.hasPermission('mesDispatchReturn:review')")
    public R<MesDispatchReturnRequestEntity> review(@RequestBody MesDispatchReturnDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @Operation(summary = "原申请人撤回待审核退派申请")
    @SysLog("撤回已接收未开工退派申请")
    @PostMapping("/withdraw")
    @PreAuthorize("@pms.hasPermission('mesDispatchReturn:apply')")
    public R<MesDispatchReturnRequestEntity> withdraw(@RequestBody MesDispatchReturnDTO.WithdrawCommand command) {
        return R.ok(service.withdraw(command));
    }
}
