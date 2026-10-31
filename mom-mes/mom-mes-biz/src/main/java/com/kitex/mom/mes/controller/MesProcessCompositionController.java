package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesProcessCompositionDTO;
import com.kitex.mom.mes.service.impl.MesProcessCompositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 过程组合的真实草稿、发布及派工运行入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesProcessComposition")
public class MesProcessCompositionController {
    private final MesProcessCompositionService service;

    @GetMapping("/capabilities")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:show')")
    public R<Map<String, Object>> capabilities() {
        return R.ok(service.capabilities());
    }

    @GetMapping("/templates")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:show')")
    public R<List<MesProcessCompositionDTO.Template>> templates() {
        return R.ok(service.templates());
    }

    @PostMapping("/draft")
    @SysLog("保存制造过程组合草稿")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:edit')")
    public R<MesProcessCompositionDTO.Template> draft(@RequestBody MesProcessCompositionDTO.Draft draft) {
        return R.ok(service.saveDraft(draft));
    }

    @PostMapping("/publish")
    @SysLog("发布制造过程组合版本")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:publish')")
    public R<MesProcessCompositionDTO.Template> publish(@RequestParam String id, @RequestParam Long expectedRevisionNo) {
        return R.ok(service.publish(id, expectedRevisionNo));
    }

    @PostMapping("/run")
    @SysLog("按制造过程组合实际派工")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesExecutionTemplate:show')")
    public R<MesProcessCompositionDTO.View> run(@RequestBody MesProcessCompositionDTO.Run command) {
        return R.ok(service.run(command));
    }

    @PostMapping("/cancel-original")
    @SysLog("作废未受理制造过程原号")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:arrange') && @pms.hasPermission('mesExecutionTemplate:show')")
    public R<MesProcessCompositionDTO.Receipt> cancelOriginal(@RequestBody MesProcessCompositionDTO.Run command) {
        return R.ok(service.cancelOriginal(command));
    }

    @GetMapping("/runs")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<List<MesProcessCompositionDTO.View>> runs() {
        return R.ok(service.runs());
    }

    @GetMapping("/requests/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesProcessCompositionDTO.Receipt> receipt(@PathVariable String requestId) {
        return R.ok(service.receipt(requestId));
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<MesProcessCompositionDTO.View> run(@PathVariable String id) {
        return R.ok(service.runView(id));
    }
}
