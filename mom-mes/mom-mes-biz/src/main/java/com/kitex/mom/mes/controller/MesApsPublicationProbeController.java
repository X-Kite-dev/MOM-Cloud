package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesApsPublicationProbeDTO;
import com.kitex.mom.mes.service.MesApsPublicationProbeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨账恢复专用只读内网契约，不暴露同步账CRUD或匿名回执查询。
 */
@RestController
@RequestMapping("/mesApsPublicationProbe/internal")
@RequiredArgsConstructor
@Tag(name = "APS跨账永久应用回执核验")
public class MesApsPublicationProbeController {
    private final MesApsPublicationProbeService service;

    @Inner
    @PostMapping("/audit")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "实名只读核对精确原应用账，不应用、补偿或修改计划")
    public R<MesApsPublicationProbeDTO.AuditReply> audit(@RequestBody MesApsPublicationProbeDTO.AuditQuery query) {
        return R.ok(service.audit(query));
    }

    @Inner
    @PostMapping("/probe")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "核对原永久命令、应用证明和当前全量MES计划，不修改现场")
    public R<MesApsPublicationProbeDTO.Reply> probe(@RequestBody MesApsPublicationProbeDTO.Query query) {
        return R.ok(service.probe(query));
    }

    @Inner
    @PostMapping("/catalog")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    @Operation(summary = "按当前真实组织及唯一案件取原发布永久号，不取末条猜有效决定")
    public R<?> catalog(@RequestBody MesApsPublicationProbeDTO.Scope scope) {
        return R.ok(service.catalog(scope));
    }
}
