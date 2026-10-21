package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.MdmProcedureToolingRequirementEntity;
import com.kitex.mom.mdm.entity.MdmToolingEntity;
import com.kitex.mom.mdm.service.MdmToolingService;
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
@RequestMapping("/mdmTooling")
@Tag(description = "mdmTooling", name = "统一工装资源")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmToolingController {
    private final MdmToolingService service;

    @GetMapping
    public R<List<MdmToolingEntity>> list(@RequestParam(required = false) String toolingType,
                                          @RequestParam(required = false) Boolean enabledOnly) {
        return R.ok(service.listToolings(toolingType, enabledOnly));
    }

    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_edit')")
    public R<Long> save(@RequestBody MdmToolingEntity tooling) {
        return R.ok(service.saveTooling(tooling));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_edit')")
    public R<Boolean> remove(@PathVariable Long id) {
        return R.ok(service.removeTooling(id));
    }

    @GetMapping("/{toolingCode}/profile")
    public R<MdmToolingProfileDTO> profile(@PathVariable String toolingCode) {
        return R.ok(service.getProfile(toolingCode));
    }

    @PutMapping("/{toolingCode}/profile")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_edit')")
    public R<Boolean> saveProfile(@PathVariable String toolingCode, @RequestBody MdmToolingProfileDTO profile) {
        return R.ok(service.saveProfile(toolingCode, profile));
    }

    @GetMapping("/routes/{routeCode}/procedures/{processCode}/requirements")
    public R<List<MdmProcedureToolingRequirementEntity>> requirements(@PathVariable String routeCode,
                                                                      @PathVariable String processCode) {
        return R.ok(service.getRequirements(routeCode, processCode));
    }

    @PutMapping("/routes/{routeCode}/procedures/{processCode}/requirements")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_edit')")
    public R<Boolean> saveRequirements(@PathVariable String routeCode, @PathVariable String processCode,
                                       @RequestBody List<MdmProcedureToolingRequirementEntity> requirements) {
        return R.ok(service.saveRequirements(routeCode, processCode, requirements));
    }

    @GetMapping("/routes/{routeCode}/nodes/{routeNodeCode}/requirements")
    public R<List<MdmProcedureToolingRequirementEntity>> nodeRequirements(@PathVariable String routeCode,
                                                                         @PathVariable String routeNodeCode) {
        return R.ok(service.getNodeRequirements(routeCode, routeNodeCode));
    }

    @PutMapping("/routes/{routeCode}/nodes/{routeNodeCode}/requirements")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_edit')")
    public R<Boolean> saveNodeRequirements(@PathVariable String routeCode, @PathVariable String routeNodeCode,
                                           @RequestBody List<MdmProcedureToolingRequirementEntity> requirements) {
        return R.ok(service.saveNodeRequirements(routeCode, routeNodeCode, requirements));
    }

    @Operation(summary = "核验工序工装候选、寿命、校准、兼容设备及不可用窗口")
    @PostMapping("/availability")
    public R<List<MdmToolingCandidateDTO>> availability(@RequestBody MdmToolingAvailabilityQueryDTO query) {
        return R.ok(service.availability(query));
    }

    @Operation(summary = "幂等登记工装实际寿命消耗")
    @Inner
    @PostMapping("/life/consume")
    public R<List<MdmToolingUsageResultDTO>> consumeLife(@RequestBody MdmToolingUsageRequestDTO request) {
        return R.ok(service.consumeLife(request));
    }
}
