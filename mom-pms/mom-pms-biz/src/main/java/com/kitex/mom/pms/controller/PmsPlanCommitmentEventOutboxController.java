package com.kitex.mom.pms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.dto.PmsPlanCommitmentEventDTO;
import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOutboxEntity;
import com.kitex.mom.pms.service.PmsPlanCommitmentEventOutboxService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsPlanCommitmentEventOutbox")
@Tag(name = "PMS供应商承诺到智能排产的可靠投递工作台")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsPlanCommitmentEventOutboxController {
    private final PmsPlanCommitmentEventOutboxService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_deliveryCommitment_view')")
    public R<Page<PmsPlanCommitmentEventOutboxEntity>> page(@ParameterObject SearchPage page,
                                                            @ParameterObject PmsPlanCommitmentEventDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{outboxId}")
    @PreAuthorize("@pms.hasPermission('pms_deliveryCommitment_view')")
    public R<PmsPlanCommitmentEventDTO.Detail> detail(@PathVariable Long outboxId) {
        return R.ok(service.detail(outboxId));
    }

    @PostMapping("/forecast")
    @SysLog("补充PMS承诺变化预计齐料时刻并进入智能排产投递")
    @PreAuthorize("@pms.hasPermission('pms_deliveryCommitment_event_handle')")
    public R<PmsPlanCommitmentEventOutboxEntity> forecast(@RequestBody PmsPlanCommitmentEventDTO.ForecastCommand command) {
        return R.ok(service.supplyForecast(command));
    }

    @PostMapping("/retry")
    @SysLog("人工重试PMS承诺变化事件投递")
    @PreAuthorize("@pms.hasPermission('pms_deliveryCommitment_event_handle')")
    public R<Boolean> retry(@RequestBody PmsPlanCommitmentEventDTO.ManualActionCommand command) {
        return R.ok(service.retry(command));
    }
}
