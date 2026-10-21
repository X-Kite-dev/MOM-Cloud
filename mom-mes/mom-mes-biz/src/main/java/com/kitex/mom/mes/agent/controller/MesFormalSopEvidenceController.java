package com.kitex.mom.mes.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.agent.dto.MesFormalSopEvidenceDTO;
import com.kitex.mom.mes.agent.service.MesFormalSopEvidenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 工单原冻结正式SOP专用合同，不改变既有旧附件引用的V1/V2语义。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAgentKnowledge")
public class MesFormalSopEvidenceController {
    private final MesFormalSopEvidenceService service;

    @Inner
    @PreAuthorize("@pms.hasPermission('ai:knowledge:view') && @pms.hasPermission('mesTask:show')")
    @PostMapping("/formal-sops")
    public R<MesFormalSopEvidenceDTO.Evidence> evidence(@Valid @RequestBody MesFormalSopEvidenceDTO.Query query) {
        return R.ok(service.evidence(query));
    }
}
