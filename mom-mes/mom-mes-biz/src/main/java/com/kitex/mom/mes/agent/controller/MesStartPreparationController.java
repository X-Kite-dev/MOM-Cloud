package com.kitex.mom.mes.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.agent.dto.MesStartPreparationDTO;
import com.kitex.mom.mes.agent.service.MesStartPreparationQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 开工准备独立入口，和正式开工命令完全分离；所有入口共用原派工查看权限。
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
public class MesStartPreparationController {
    private final MesStartPreparationQueryService service;

    /**
     * 分页选择真实派工，不复用报工行的主键。
     */
    @GetMapping("/mesTask/arrange/start-preparation/candidates")
    public R<Map<String, Object>> candidates(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String taskCode,
                                             @RequestParam(required = false) String dispatchStatus,
                                             @RequestParam(required = false) String lastStatus,
                                             @RequestParam(defaultValue = "false") boolean includeLastCheck) {
        return R.ok(service.candidates(current, size, keyword, taskCode, dispatchStatus, lastStatus, includeLastCheck));
    }

    @PostMapping("/mesTask/arrange/start-preparation")
    public R<Map<String, Object>> preview(@Valid @RequestBody MesStartPreparationDTO.Query query) {
        return R.ok(service.query(query));
    }

    @GetMapping("/mesTask/arrange/start-preparation/request/{requestId}")
    public R<Map<String, Object>> receipt(@PathVariable String requestId) {
        return R.ok(service.receipt(requestId));
    }

    /**
     * 工具观察也必须持有真实用户和原业务权限，不以内部请求头代替授权。
     */
    @Inner
    @PostMapping("/mesAgentQuery/internal/start-preparation")
    public R<Map<String, Object>> query(@Valid @RequestBody MesStartPreparationDTO.Query query) {
        return R.ok(service.query(query));
    }

    @Inner
    @PostMapping("/mesAgentQuery/internal/start-preparation/snapshot")
    public R<Map<String, Object>> snapshot(@Valid @RequestBody MesStartPreparationDTO.SnapshotQuery query) {
        return R.ok(service.snapshot(query));
    }
}
