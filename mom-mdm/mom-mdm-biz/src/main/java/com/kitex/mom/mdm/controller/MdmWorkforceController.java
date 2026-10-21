package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmEmployeeWorkforceProfileDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceAvailabilityQueryDTO;
import com.kitex.mom.mdm.dto.MdmWorkforceCandidateDTO;
import com.kitex.mom.mdm.entity.MdmProcedureSkillRequirementEntity;
import com.kitex.mom.mdm.entity.MdmSkillEntity;
import com.kitex.mom.mdm.service.MdmWorkforceService;
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
@RequestMapping("/mdmWorkforce")
@Tag(description = "mdmWorkforce", name = "人员能力与排班")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmWorkforceController {
    private final MdmWorkforceService workforceService;

    @GetMapping("/skills")
    public R<List<MdmSkillEntity>> listSkills(@RequestParam(required = false) Boolean enabledOnly) {
        return R.ok(workforceService.listSkills(enabledOnly));
    }

    @Operation(summary = "新增或修改技能字典")
    @PostMapping("/skills")
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_edit')")
    public R<Long> saveSkill(@RequestBody MdmSkillEntity skill) {
        return R.ok(workforceService.saveSkill(skill));
    }

    @DeleteMapping("/skills/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_edit')")
    public R<Boolean> removeSkill(@PathVariable Long id) {
        return R.ok(workforceService.removeSkill(id));
    }

    @GetMapping("/employees/{employeeCode}/profile")
    public R<MdmEmployeeWorkforceProfileDTO> getEmployeeProfile(@PathVariable String employeeCode) {
        return R.ok(workforceService.getEmployeeProfile(employeeCode));
    }

    @PutMapping("/employees/{employeeCode}/profile")
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_edit')")
    public R<Boolean> saveEmployeeProfile(@PathVariable String employeeCode,
                                          @RequestBody MdmEmployeeWorkforceProfileDTO profile) {
        profile.setEmployeeCode(employeeCode);
        return R.ok(workforceService.saveEmployeeProfile(profile));
    }

    @GetMapping("/procedures/{processCode}/requirements")
    public R<List<MdmProcedureSkillRequirementEntity>> getProcedureRequirements(@PathVariable String processCode) {
        return R.ok(workforceService.getProcedureRequirements(processCode));
    }

    @PutMapping("/procedures/{processCode}/requirements")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_edit')")
    public R<Boolean> saveProcedureRequirements(@PathVariable String processCode,
                                                @RequestBody List<MdmProcedureSkillRequirementEntity> requirements) {
        return R.ok(workforceService.saveProcedureRequirements(processCode, requirements));
    }

    @Operation(summary = "按员工、工序和时间范围计算人员资质及在岗窗口")
    @PostMapping("/availability")
    public R<List<MdmWorkforceCandidateDTO>> availability(@RequestBody MdmWorkforceAvailabilityQueryDTO query) {
        return R.ok(workforceService.availability(query));
    }
}
