package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesAutoReportEventDTO;
import com.kitex.mom.mes.dto.MesAutoReportResultDTO;
import com.kitex.mom.mes.dto.MesAutoReportRetryDTO;
import com.kitex.mom.mes.entity.MesAutoReportEventEntity;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;
import com.kitex.mom.mes.service.MesAutoReportEventService;
import com.kitex.mom.mes.service.MesAutoReportRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(description = "mesAutoReport", name = "设备自动报工")
@RestController
@RequestMapping("/mesAutoReport")
@RequiredArgsConstructor
public class MesAutoReportController {

    private final MesAutoReportRuleService ruleService;
    private final MesAutoReportEventService eventService;
    private final com.kitex.mom.mes.service.impl.MesActualFactoryAccessService actualAccess;

    @PostMapping("/internal/receipt")
    @Inner
    @Operation(summary = "按完整来源事实核验永久回执，不触发业务重试")
    public R<MesAutoReportResultDTO> receiptInternal(@RequestBody MesAutoReportEventDTO event) {
        return R.ok(eventService.receipt(event));
    }

    @PostMapping("/internal/event")
    @Inner
    @Operation(summary = "IoT或内部设备网关提交产量事件")
    public R<MesAutoReportResultDTO> acceptInternal(@RequestBody MesAutoReportEventDTO event) {
        return R.ok(eventService.accept(event));
    }

    @PostMapping("/event")
    @Operation(summary = "受控集成API提交产量事件")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:event')")
    public R<MesAutoReportResultDTO> accept(@RequestBody MesAutoReportEventDTO event) {
        actualAccess.require("write");
        return R.ok(eventService.accept(event));
    }

    @GetMapping("/rule/page")
    @Operation(summary = "自动报工规则分页")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:show')")
    public R<IPage<MesAutoReportRuleEntity>> rulePage(Page<MesAutoReportRuleEntity> page,
                                                      MesAutoReportRuleEntity query) {
        return R.ok(ruleService.queryPage(page, query));
    }

    @PostMapping("/rule/save")
    @Operation(summary = "新增或修改自动报工规则")
    @SysLog("保存设备自动报工规则")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:edit')")
    public R<MesAutoReportRuleEntity> saveRule(@RequestBody MesAutoReportRuleEntity rule) {
        return R.ok(ruleService.saveRule(rule));
    }

    @PostMapping("/rule/status")
    @Operation(summary = "启用或停用自动报工规则")
    @SysLog("变更设备自动报工规则状态")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:edit')")
    public R<MesAutoReportRuleEntity> changeStatus(@RequestParam Long id, @RequestParam boolean enabled) {
        return R.ok(ruleService.changeStatus(id, enabled));
    }

    @DeleteMapping("/rule")
    @Operation(summary = "删除无成功历史的停用规则")
    @SysLog("删除设备自动报工规则")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:edit')")
    public R<Void> deleteRule(@RequestParam Long id) {
        ruleService.deleteRule(id);
        return R.ok();
    }

    @GetMapping("/event/page")
    @Operation(summary = "自动报工事件台账分页")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:show')")
    public R<IPage<MesAutoReportEventEntity>> eventPage(Page<MesAutoReportEventEntity> page,
                                                        MesAutoReportEventEntity query) {
        return R.ok(eventService.queryPage(page, query));
    }

    @PostMapping("/event/retry")
    @Operation(summary = "补偿重试失败或忽略的自动报工事件")
    @SysLog("重试设备自动报工事件")
    @PreAuthorize("@pms.hasPermission('mesAutoReport:retry')")
    public R<MesAutoReportResultDTO> retry(@RequestBody MesAutoReportRetryDTO command) {
        return R.ok(eventService.retry(command));
    }
}
