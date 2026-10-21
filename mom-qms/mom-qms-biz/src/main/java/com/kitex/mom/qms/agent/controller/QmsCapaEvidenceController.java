package com.kitex.mom.qms.agent.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.agent.dto.QmsCapaEvidenceDTO;
import com.kitex.mom.qms.agent.service.QmsCapaEvidenceQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 8D 报告只读证据入口，POST兼容现有领域工具传输器，GET供实名查询。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/qmsAgentQuery/internal/capa-evidence")
public class QmsCapaEvidenceController {
    private final QmsCapaEvidenceQueryService service;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<QmsCapaEvidenceDTO.Evidence> query(@RequestParam @Positive Long capaId) {
        return R.ok(service.query(capaId));
    }

    @Inner
    @PostMapping
    @PreAuthorize("@pms.hasPermission('capa:show')")
    public R<QmsCapaEvidenceDTO.Evidence> query(@Valid @RequestBody QmsCapaEvidenceDTO.Query request) {
        return R.ok(service.query(request.getCapaId()));
    }
}
