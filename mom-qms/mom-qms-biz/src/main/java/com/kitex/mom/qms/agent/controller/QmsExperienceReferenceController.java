package com.kitex.mom.qms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.agent.dto.QmsExperienceReferenceCommand;
import com.kitex.mom.qms.agent.service.QmsExperienceReferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CAPA经验引用的正式业务入口和原号/分页回执，保持原案件权限。
 */
@RestController
@RequestMapping("/qmsAgentExperience")
@RequiredArgsConstructor
public class QmsExperienceReferenceController {
    private final QmsExperienceReferenceService service;

    @Inner
    @PostMapping("/internal/reference")
    @PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('capa:analyze') and @pms.hasPermission('ai:8d:view')")
    public R<?> reference(@Valid @RequestBody QmsExperienceReferenceCommand command) {
        return R.ok(service.reference(command));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('ai:8d:view')")
    public R<?> page(@RequestParam Long capaId, @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size,
                     @RequestParam(required = false) Integer cycleNo) {
        return R.ok(service.page(capaId, current, size, cycleNo));
    }

    /** 仅查看原案件进展，不登记采纳、不核账或修改质量状态。 */
    @GetMapping("/followup/{referenceId}")
    @PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('ai:8d:view')")
    public R<?> followup(@PathVariable Long referenceId, @RequestParam Long capaId,
                         @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.followup(referenceId, capaId, current, size));
    }

    @GetMapping("/request/{requestId}")
    @PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('ai:8d:view')")
    public R<?> request(@PathVariable String requestId) {
        return R.ok(service.request(requestId));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('capa:analyze') and @pms.hasPermission('ai:8d:view')")
    public R<?> seal(@Valid @RequestBody Cancel command) {
        return R.ok(service.seal(command.requestId()));
    }

    @Inner
    @PostMapping("/internal/reconcile")
    @PreAuthorize("@pms.hasPermission('capa:show') and @pms.hasPermission('ai:8d:view')")
    public R<?> reconcile(@Valid @RequestBody Reconcile command) {
        return R.ok(service.reconcile(command.requestId(), command.cancel()));
    }

    public record Reconcile(@jakarta.validation.constraints.NotBlank
                            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._:-]{8,100}") String requestId,
                            boolean cancel) {
    }

    public record Cancel(@jakarta.validation.constraints.NotBlank
                         @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._:-]{8,100}") String requestId) {
    }
}
