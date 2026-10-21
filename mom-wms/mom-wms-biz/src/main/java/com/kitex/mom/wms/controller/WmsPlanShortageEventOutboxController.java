package com.kitex.mom.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsPlanShortageEventDTO;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOutboxEntity;
import com.kitex.mom.wms.service.WmsPlanShortageEventOutboxService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsPlanShortageEventOutbox")
@Tag(name = "WMS供应失效到智能排产的可靠投递工作台")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsPlanShortageEventOutboxController {
    private final WmsPlanShortageEventOutboxService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_planShortageEvent_view')")
    public R<Page<WmsPlanShortageEventOutboxEntity>> page(@ParameterObject SearchPage page,
                                                          @ParameterObject WmsPlanShortageEventDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{outboxId}")
    @PreAuthorize("@pms.hasPermission('wms_planShortageEvent_view')")
    public R<WmsPlanShortageEventDTO.Detail> detail(@PathVariable Long outboxId) {
        return R.ok(service.detail(outboxId));
    }

    @PostMapping("/forecast")
    @SysLog("补充WMS缺料预计供应时刻并进入智能排产投递")
    @PreAuthorize("@pms.hasPermission('wms_planShortageEvent_forecast')")
    public R<WmsPlanShortageEventOutboxEntity> forecast(
            @RequestBody WmsPlanShortageEventDTO.ForecastCommand command) {
        return R.ok(service.supplyForecast(command));
    }

    @PostMapping("/retry")
    @SysLog("人工重试WMS缺料事件投递")
    @PreAuthorize("@pms.hasPermission('wms_planShortageEvent_handle')")
    public R<Boolean> retry(@RequestBody WmsPlanShortageEventDTO.ManualActionCommand command) {
        return R.ok(service.retry(command));
    }
}
