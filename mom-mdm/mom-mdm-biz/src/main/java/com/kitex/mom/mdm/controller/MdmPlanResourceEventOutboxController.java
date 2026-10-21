package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmPlanResourceEventDTO;
import com.kitex.mom.mdm.entity.MdmPlanResourceEventOutboxEntity;
import com.kitex.mom.mdm.service.MdmPlanResourceEventOutboxService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmPlanResourceEventOutbox")
@Tag(name = "MDM工装人员异常到智能排产的可靠投递工作台")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmPlanResourceEventOutboxController {
    private final MdmPlanResourceEventOutboxService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_planResourceEvent_view')")
    public R<Page<MdmPlanResourceEventOutboxEntity>> page(@ParameterObject SearchPage page,
                                                          @ParameterObject MdmPlanResourceEventDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{outboxId}")
    @PreAuthorize("@pms.hasPermission('mdm_planResourceEvent_view')")
    public R<MdmPlanResourceEventDTO.Detail> detail(@PathVariable Long outboxId) {
        return R.ok(service.detail(outboxId));
    }

    @PostMapping("/retry")
    @SysLog("人工重试MDM资源事件投递")
    @PreAuthorize("@pms.hasPermission('mdm_planResourceEvent_handle')")
    public R<Boolean> retry(@RequestBody MdmPlanResourceEventDTO.ManualActionCommand command) {
        return R.ok(service.retry(command));
    }
}
