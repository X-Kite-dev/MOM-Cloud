package com.kitex.mom.tpm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.dto.TpmPlanEventDTO;
import com.kitex.mom.tpm.entity.TpmPlanEventOutboxEntity;
import com.kitex.mom.tpm.service.TpmPlanEventOutboxService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmPlanEventOutbox")
@Tag(name = "TPM设备异常到智能排产的可靠投递工作台")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmPlanEventOutboxController {
    private final TpmPlanEventOutboxService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_planEventOutbox_view')")
    public R<Page<TpmPlanEventOutboxEntity>> page(@ParameterObject SearchPage page,
                                                  @ParameterObject TpmPlanEventDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{outboxId}")
    @PreAuthorize("@pms.hasPermission('tpm_planEventOutbox_view')")
    public R<TpmPlanEventDTO.Detail> detail(@PathVariable Long outboxId) {
        return R.ok(service.detail(outboxId));
    }

    @PostMapping("/forecast")
    @SysLog("补充设备恢复预测并进入智能排产投递")
    @PreAuthorize("@pms.hasPermission('tpm_planEventOutbox_forecast')")
    public R<TpmPlanEventOutboxEntity> forecast(@RequestBody TpmPlanEventDTO.ForecastCommand command) {
        return R.ok(service.supplyForecast(command));
    }

    @PostMapping("/retry")
    @SysLog("人工重试设备事件投递")
    @PreAuthorize("@pms.hasPermission('tpm_planEventOutbox_handle')")
    public R<Boolean> retry(@RequestBody TpmPlanEventDTO.ManualActionCommand command) {
        return R.ok(service.retry(command));
    }

    @PostMapping("/cancel")
    @SysLog("人工取消设备事件投递")
    @PreAuthorize("@pms.hasPermission('tpm_planEventOutbox_handle')")
    public R<Boolean> cancel(@RequestBody TpmPlanEventDTO.ManualActionCommand command) {
        return R.ok(service.cancel(command));
    }
}
