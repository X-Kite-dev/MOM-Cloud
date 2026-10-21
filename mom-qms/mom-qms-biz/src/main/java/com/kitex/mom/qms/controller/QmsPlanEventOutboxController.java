package com.kitex.mom.qms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.qms.dto.QmsPlanEventDTO;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;
import com.kitex.mom.qms.service.QmsPlanEventOutboxService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/qmsPlanEventOutbox")
@Tag(name = "QMS质量事实到智能排产的可靠投递工作台")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class QmsPlanEventOutboxController {
    private final QmsPlanEventOutboxService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('qms_planEventOutbox_view')")
    public R<Page<QmsPlanEventOutboxEntity>> page(@ParameterObject SearchPage page,
                                                  @ParameterObject QmsPlanEventDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{outboxId}")
    @PreAuthorize("@pms.hasPermission('qms_planEventOutbox_view')")
    public R<QmsPlanEventDTO.Detail> detail(@PathVariable Long outboxId) {
        return R.ok(service.detail(outboxId));
    }

    @PostMapping("/retry")
    @SysLog("人工重试QMS质量事实投递")
    @PreAuthorize("@pms.hasPermission('qms_planEventOutbox_handle')")
    public R<Boolean> retry(@RequestBody QmsPlanEventDTO.ManualActionCommand command) {
        return R.ok(service.retry(command));
    }
}
