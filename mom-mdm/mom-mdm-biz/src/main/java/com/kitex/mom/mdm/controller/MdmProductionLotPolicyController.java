package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyActionDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogDTO;
import com.kitex.mom.mdm.dto.MdmProductionLotPolicyCatalogQueryDTO;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyEntity;
import com.kitex.mom.mdm.entity.MdmProductionLotPolicyLogEntity;
import com.kitex.mom.mdm.service.MdmProductionLotPolicyService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 生产批量策略的草稿编制、异岗启用和正式目录接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmProductionLotPolicy")
public class MdmProductionLotPolicyController {
    private final MdmProductionLotPolicyService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_productionLotPolicy_view')")
    public R<Page<MdmProductionLotPolicyEntity>> page(SearchPage page,
                                                      @ParameterObject MdmProductionLotPolicyEntity query) {
        return R.ok(service.pagePolicies(page, query));
    }

    /**
     * 编辑或复核前重新读取当前工厂的版本，并返回并发校验令牌。
     */
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_productionLotPolicy_view')")
    public R<MdmProductionLotPolicyEntity> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/next-version")
    @PreAuthorize("@pms.hasPermission('mdm_productionLotPolicy_edit')")
    public R<Integer> nextVersion(@PathVariable Long id) {
        return R.ok(service.nextVersion(id));
    }

    @GetMapping("/{id}/logs")
    @PreAuthorize("@pms.hasPermission('mdm_productionLotPolicy_view')")
    public R<List<MdmProductionLotPolicyLogEntity>> logs(@PathVariable Long id) {
        return R.ok(service.logs(id));
    }

    @PostMapping
    @SysLog("新增生产批量策略草稿")
    @PreAuthorize("@pms.hasPermission('mdm_productionLotPolicy_edit')")
    public R<Long> save(@RequestBody MdmProductionLotPolicyEntity entity) {
        return R.ok(service.saveDraft(entity));
    }

    @PutMapping
    @SysLog("修改生产批量策略草稿")
    @PreAuthorize("@pms.hasPermission('mdm_productionLotPolicy_edit')")
    public R<Boolean> update(@RequestBody MdmProductionLotPolicyEntity entity) {
        return R.ok(service.updateDraft(entity));
    }

    @PutMapping("/status")
    @SysLog("启用、暂停或退役生产批量策略")
    @PreAuthorize("@pms.hasPermission('mdm_productionLotPolicy_approve')")
    public R<Boolean> status(@RequestBody MdmProductionLotPolicyActionDTO action) {
        return R.ok(service.changeStatus(action));
    }

    @PostMapping("/catalog")
    @Inner
    public R<MdmProductionLotPolicyCatalogDTO> catalog(
            @RequestBody MdmProductionLotPolicyCatalogQueryDTO query) {
        return R.ok(service.catalog(query));
    }
}
