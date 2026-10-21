package com.kitex.mom.pms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.dto.PmsContractRevisionDTO;
import com.kitex.mom.pms.service.PmsContractRevisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsContractRevision")
@Tag(description = "pmsContractRevision", name = "采购合同正文版本变更")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsContractRevisionController {
    private final PmsContractRevisionService service;

    @PostMapping("/preview")
    @Operation(summary = "重采合同正文变更证据并生成差异预览")
    @PreAuthorize("@pms.hasPermission('pms_contractRevision_view')")
    public R<PmsContractRevisionDTO.Preview> preview(@RequestBody PmsContractRevisionDTO.ContentCommand command) {
        return R.ok(service.preview(command));
    }

    @PostMapping("/submit")
    @SysLog("提交采购合同正文版本变更")
    @PreAuthorize("@pms.hasPermission('pms_contractRevision_submit')")
    public R<PmsContractRevisionDTO.Detail> submit(@RequestBody PmsContractRevisionDTO.SubmitCommand command) {
        return R.ok(service.submit(command));
    }

    @PostMapping("/review")
    @SysLog("复核采购合同正文版本变更")
    @PreAuthorize("@pms.hasPermission('pms_contractRevision_review')")
    public R<PmsContractRevisionDTO.Detail> review(@RequestBody PmsContractRevisionDTO.ReviewCommand command) {
        return R.ok(service.review(command));
    }

    @PostMapping("/withdraw")
    @SysLog("撤回采购合同正文版本变更")
    @PreAuthorize("@pms.hasPermission('pms_contractRevision_submit')")
    public R<PmsContractRevisionDTO.Detail> withdraw(@RequestBody PmsContractRevisionDTO.WithdrawCommand command) {
        return R.ok(service.withdraw(command));
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("@pms.hasPermission('pms_contractRevision_view')")
    public R<PmsContractRevisionDTO.Detail> detail(@PathVariable Long requestId) {
        return R.ok(service.detail(requestId));
    }

    @GetMapping("/contract/{contractId}")
    @PreAuthorize("@pms.hasPermission('pms_contractRevision_view')")
    public R<?> list(@PathVariable Long contractId) {
        return R.ok(service.listByContract(contractId));
    }

    @GetMapping("/contract/{contractId}/versions")
    @PreAuthorize("@pms.hasPermission('pms_contractRevision_view')")
    public R<?> versions(@PathVariable Long contractId) {
        return R.ok(service.versions(contractId));
    }
}
