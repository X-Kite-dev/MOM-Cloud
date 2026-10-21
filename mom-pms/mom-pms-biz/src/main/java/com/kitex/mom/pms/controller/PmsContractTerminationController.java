package com.kitex.mom.pms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.dto.PmsContractTerminationDTO;
import com.kitex.mom.pms.entity.PmsContractTerminationRequestEntity;
import com.kitex.mom.pms.service.PmsContractTerminationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsContractTermination")
@Tag(description = "pmsContractTermination", name = "采购合同受控终止")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsContractTerminationController {
    private final PmsContractTerminationService service;

    @GetMapping("/preview/{contractId}")
    @Operation(summary = "预览合同终止及下游阻断")
    @PreAuthorize("@pms.hasPermission('pms_contractTermination_view')")
    public R<PmsContractTerminationDTO.Preview> preview(@PathVariable Long contractId) {
        return R.ok(service.preview(contractId));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交采购合同终止申请")
    @SysLog("提交采购合同终止申请")
    @PreAuthorize("@pms.hasPermission('pms_contractTermination_submit')")
    public R<PmsContractTerminationDTO.Detail> submit(@RequestBody PmsContractTerminationDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/review")
    @Operation(summary = "异岗复核并执行采购合同终止")
    @SysLog("复核采购合同终止申请")
    @PreAuthorize("@pms.hasPermission('pms_contractTermination_review')")
    public R<PmsContractTerminationDTO.Detail> review(@RequestBody PmsContractTerminationDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "查询合同终止申请与操作履历")
    @PreAuthorize("@pms.hasPermission('pms_contractTermination_view')")
    public R<PmsContractTerminationDTO.Detail> detail(@PathVariable Long requestId) {
        return R.ok(service.detail(requestId));
    }

    @GetMapping("/contract/{contractId}")
    @Operation(summary = "查询合同全部终止申请")
    @PreAuthorize("@pms.hasPermission('pms_contractTermination_view')")
    public R<List<PmsContractTerminationRequestEntity>> listByContract(@PathVariable Long contractId) {
        return R.ok(service.listByContract(contractId));
    }
}
