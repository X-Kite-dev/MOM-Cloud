package com.kitex.mom.mdm.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningApsReferencesDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyCommand;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.mdm.service.MdmFactoryPlanningPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工厂建模中的计划协同策略接口，不建立独立顶级业务菜单。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmFactoryPlanningPolicy")
public class MdmFactoryPlanningPolicyController {
    private final MdmFactoryPlanningPolicyService service;

    @GetMapping("/active")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R<MdmFactoryPlanningPolicyDTO> active(@RequestParam String factoryCode) {
        return R.ok(service.requireActive(factoryCode));
    }

    @GetMapping("/history/{factoryModelId}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R<List<MdmFactoryPlanningPolicyDTO>> history(@PathVariable String factoryModelId) {
        return R.ok(service.history(factoryModelId));
    }

    @PostMapping("/draft")
    @SysLog("创建工厂计划协同策略草稿")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_edit')")
    public R<MdmFactoryPlanningPolicyDTO> createDraft(
            @RequestBody MdmFactoryPlanningPolicyCommand.CreateDraft command) {
        return R.ok(service.createDraft(command));
    }

    @GetMapping("/impact/{draftId}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R<MdmFactoryPlanningImpactDTO> inspect(@PathVariable String draftId) {
        return R.ok(service.inspect(draftId));
    }

    @GetMapping("/apsReferences/{draftId}")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_view')")
    public R<MdmFactoryPlanningApsReferencesDTO> apsReferences(@PathVariable String draftId) {
        return R.ok(service.apsReferences(draftId));
    }

    @PostMapping("/publish")
    @SysLog("发布工厂计划协同策略")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_edit')")
    public R<MdmFactoryPlanningPolicyDTO> publish(
            @RequestBody MdmFactoryPlanningPolicyCommand.Publish command) {
        return R.ok(service.publish(command));
    }
}
