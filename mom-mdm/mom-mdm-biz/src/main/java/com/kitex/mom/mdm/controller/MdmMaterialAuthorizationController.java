package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmMaterialAuthorizationActionDTO;
import com.kitex.mom.mdm.dto.MdmMaterialAuthorizationSummaryDTO;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationEntity;
import com.kitex.mom.mdm.entity.MdmMaterialAuthorizationLogEntity;
import com.kitex.mom.mdm.mapper.MdmMaterialAuthorizationLogMapper;
import com.kitex.mom.mdm.service.MdmMaterialAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmMaterialAuthorization")
@Tag(description = "mdmMaterialAuthorization", name = "替代料物料授权台账")
public class MdmMaterialAuthorizationController {
    private final MdmMaterialAuthorizationService service;
    private final MdmMaterialAuthorizationLogMapper logMapper;

    @GetMapping("/page")
    @Operation(summary = "授权台账分页及有效期预警")
    @PreAuthorize("@pms.hasPermission('mdm_materialAuthorization_view')")
    public R<Page<MdmMaterialAuthorizationEntity>> page(SearchPage page,
                                                        @ParameterObject MdmMaterialAuthorizationEntity query) {
        Page<MdmMaterialAuthorizationEntity> result = service.page(page.toMpPage(),
                new LambdaQueryWrapper<MdmMaterialAuthorizationEntity>()
                        .eq(StrUtil.isNotBlank(query.getAuthorizationType()), MdmMaterialAuthorizationEntity::getAuthorizationType, query.getAuthorizationType())
                        .eq(StrUtil.isNotBlank(query.getPartnerCode()), MdmMaterialAuthorizationEntity::getPartnerCode, query.getPartnerCode())
                        .like(StrUtil.isNotBlank(query.getPartnerName()), MdmMaterialAuthorizationEntity::getPartnerName, query.getPartnerName())
                        .eq(StrUtil.isNotBlank(query.getMaterialCode()), MdmMaterialAuthorizationEntity::getMaterialCode, query.getMaterialCode())
                        .like(StrUtil.isNotBlank(query.getMaterialName()), MdmMaterialAuthorizationEntity::getMaterialName, query.getMaterialName())
                        .eq(StrUtil.isNotBlank(query.getStatus()), MdmMaterialAuthorizationEntity::getStatus, query.getStatus())
                        .orderByAsc(MdmMaterialAuthorizationEntity::getValidEnd)
                        .orderByDesc(MdmMaterialAuthorizationEntity::getCreateTime));
        result.getRecords().forEach(service::enrichValidity);
        return R.ok(result);
    }

    @GetMapping("/active")
    @Inner
    @Operation(summary = "按业务日期查询有效授权，供BOM/QMS/WMS调用")
    public R<List<MdmMaterialAuthorizationEntity>> active(@RequestParam String authorizationType,
                                                          @RequestParam String materialCode,
                                                          @RequestParam(required = false) String partnerCode,
                                                          @RequestParam(required = false) LocalDate bizDate) {
        return R.ok(service.active(authorizationType, materialCode, partnerCode, bizDate));
    }

    @GetMapping("/summary")
    @Operation(summary = "授权有效期预警汇总")
    @PreAuthorize("@pms.hasPermission('mdm_materialAuthorization_view')")
    public R<MdmMaterialAuthorizationSummaryDTO> summary(@RequestParam(required = false) String authorizationType,
                                                         @RequestParam(required = false) String partnerCode,
                                                         @RequestParam(required = false) String materialCode) {
        return R.ok(service.summary(authorizationType, partnerCode, materialCode));
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "查询授权全生命周期审计日志")
    @PreAuthorize("@pms.hasPermission('mdm_materialAuthorization_view')")
    public R<List<MdmMaterialAuthorizationLogEntity>> logs(@PathVariable Long id) {
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<MdmMaterialAuthorizationLogEntity>()
                .eq(MdmMaterialAuthorizationLogEntity::getAuthorizationId, id)
                .orderByDesc(MdmMaterialAuthorizationLogEntity::getOperatedTime)));
    }

    @PostMapping
    @SysLog("新增替代料物料授权草稿")
    @PreAuthorize("@pms.hasPermission('mdm_materialAuthorization_add')")
    public R<Long> save(@RequestBody MdmMaterialAuthorizationEntity entity) {
        return R.ok(service.saveDraft(entity));
    }

    @PutMapping
    @SysLog("修改替代料物料授权草稿")
    @PreAuthorize("@pms.hasPermission('mdm_materialAuthorization_edit')")
    public R<Boolean> update(@RequestBody MdmMaterialAuthorizationEntity entity) {
        return R.ok(service.updateDraft(entity));
    }

    @PutMapping("/status")
    @SysLog("变更替代料物料授权状态")
    @PreAuthorize("@pms.hasPermission('mdm_materialAuthorization_approve')")
    public R<Boolean> status(@RequestBody MdmMaterialAuthorizationActionDTO action) {
        return R.ok(service.changeStatus(action));
    }
}
