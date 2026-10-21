package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardEntity;
import com.kitex.mom.mdm.service.MdmPhysicalTransformStandardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 真实转换工程版本管理；原JSON严格解析，发布证据不能通过实体CRUD提交。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmPhysicalTransformStandard")
public class MdmPhysicalTransformStandardController {
    private final MdmPhysicalTransformStandardService service;

    /** 实际组织内分页查询。 */
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_view')")
    public R<Page<MdmPhysicalTransformStandardEntity>> page(SearchPage page,
            @RequestParam(required = false) String standardNo, @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String status) {
        return R.ok(service.pageStandards(page, standardNo, materialCode, status));
    }

    /** 实际启用路线、路线工序或单位换算的分页选择，所有依据身份保持文本。 */
    @GetMapping("/basis/page")
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_view')")
    public R<Page<MdmPhysicalTransformStandardDTO.BasisOption>> basis(SearchPage page, @RequestParam String kind,
            @RequestParam String materialCode, @RequestParam(required = false) String routeId) {
        return R.ok(service.basisPage(page, kind, materialCode, routeId));
    }

    /** 精确版本及完整编制/审批履历。 */
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_view')")
    public R<MdmPhysicalTransformStandardDTO.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    /** 编制或修改草稿，待复核不可覆盖。 */
    @PostMapping(value = "/draft", consumes = "application/json")
    @SysLog("编制实物转换工程标准")
    @PreAuthorize("@pms.hasPermission('mdm_physicalTransformStandard_edit')")
    public R<MdmPhysicalTransformStandardEntity> draft(@RequestBody String rawJson) {
        return R.ok(service.saveDraft(rawJson));
    }

    /** 服务端按动作独立核编制或审批岗位，无通用状态更新。 */
    @PostMapping(value = "/action", consumes = "application/json")
    @SysLog("实物转换工程版本动作")
    @PreAuthorize("isAuthenticated()")
    public R<MdmPhysicalTransformStandardEntity> action(@RequestBody String rawJson) {
        return R.ok(service.act(rawJson));
    }

    /** 内部标记不替代服务端实名/scope/已发布正文核验。 */
    @Inner
    @PostMapping("/internal/resolve")
    @PreAuthorize("isAuthenticated()")
    public R<MdmPhysicalTransformStandardDTO.Frozen> resolve(@RequestBody MdmPhysicalTransformStandardDTO.ResolveQuery query) {
        return R.ok(service.resolveReleased(query));
    }
}
