package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesCaseDispositionCommand;
import com.kitex.mom.mes.entity.MesCaseDispositionReceiptEntity;
import com.kitex.mom.mes.entity.MesInventoryQualityCaseEntity;
import com.kitex.mom.mes.service.impl.MesCaseDispositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesInventoryQualityCase/internal")
public class MesInventoryQualityCaseController {
    private final MesCaseDispositionService service;

    @Inner
    @PostMapping("/receipt-read")
    public R<com.kitex.mom.mes.dto.MesCaseReceiptBundle> read(@RequestBody com.kitex.mom.mes.dto.MesCaseReceiptRead request) {
        return R.ok(service.readSigned(request));
    }

    @Inner
    @GetMapping("/receipt-chain/{caseId}/{ncrId}/{detailId}/{decisionKey}")
    public R<com.kitex.mom.mes.dto.MesCaseReceiptBundle> chain(@PathVariable Long caseId, @PathVariable Long ncrId, @PathVariable Long detailId, @PathVariable String decisionKey) {
        return R.ok(service.chain(caseId, ncrId, detailId, decisionKey));
    }

    @Inner
    @GetMapping("/{id}")
    public R<MesInventoryQualityCaseEntity> source(@PathVariable Long id) {
        return R.ok(service.source(id));
    }

    @Inner
    @PostMapping("/disposition")
    @PreAuthorize("@pms.hasPermission(#command.stage == 'EXECUTE' ? 'bad:mes:execute' : 'bad:verify')")
    public R<MesCaseDispositionReceiptEntity> execute(@RequestBody MesCaseDispositionCommand command) {
        return R.ok(service.execute(command));
    }

    @Inner
    @GetMapping("/disposition/{detailId}/{decisionKey}")
    public R<MesCaseDispositionReceiptEntity> latest(@PathVariable Long detailId, @PathVariable String decisionKey) {
        return R.ok(service.latest(detailId, decisionKey));
    }
}
