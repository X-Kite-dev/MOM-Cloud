package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesDurationEvidenceDTO;
import com.kitex.mom.mes.service.MesDurationEvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesDurationEvidence")
@Tag(name = "MES实际工时校准证据")
public class MesDurationEvidenceController {
    private final MesDurationEvidenceService service;

    @Inner
    @PostMapping("/internal/query")
    @Operation(summary = "读取可重采的加工与换型实际时间证据")
    public R<MesDurationEvidenceDTO.Bundle> query(@RequestBody MesDurationEvidenceDTO.Query query) {
        return R.ok(service.query(query));
    }
}
