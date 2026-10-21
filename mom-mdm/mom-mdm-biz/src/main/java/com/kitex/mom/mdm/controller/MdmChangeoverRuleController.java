package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverCatalogQueryDTO;
import com.kitex.mom.mdm.dto.MdmChangeoverRuleActionDTO;
import com.kitex.mom.mdm.entity.MdmChangeoverRuleEntity;
import com.kitex.mom.mdm.entity.MdmChangeoverRuleLogEntity;
import com.kitex.mom.mdm.mapper.MdmChangeoverRuleLogMapper;
import com.kitex.mom.mdm.service.MdmChangeoverRuleService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmChangeoverRule")
public class MdmChangeoverRuleController {
    private final MdmChangeoverRuleService service;
    private final MdmChangeoverRuleLogMapper logMapper;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_changeoverRule_view')")
    public R<Page<MdmChangeoverRuleEntity>> page(SearchPage page,
                                                 @ParameterObject MdmChangeoverRuleEntity query) {
        return R.ok(service.page(page.toMpPage(), new LambdaQueryWrapper<MdmChangeoverRuleEntity>()
                .like(StrUtil.isNotBlank(query.getRuleNo()), MdmChangeoverRuleEntity::getRuleNo, query.getRuleNo())
                .like(StrUtil.isNotBlank(query.getRuleName()), MdmChangeoverRuleEntity::getRuleName, query.getRuleName())
                .eq(StrUtil.isNotBlank(query.getScopeType()), MdmChangeoverRuleEntity::getScopeType, query.getScopeType())
                .eq(StrUtil.isNotBlank(query.getResourceCode()), MdmChangeoverRuleEntity::getResourceCode, query.getResourceCode())
                .eq(StrUtil.isNotBlank(query.getProcessCode()), MdmChangeoverRuleEntity::getProcessCode, query.getProcessCode())
                .eq(StrUtil.isNotBlank(query.getFromFamilyCode()), MdmChangeoverRuleEntity::getFromFamilyCode, query.getFromFamilyCode())
                .eq(StrUtil.isNotBlank(query.getToFamilyCode()), MdmChangeoverRuleEntity::getToFamilyCode, query.getToFamilyCode())
                .eq(StrUtil.isNotBlank(query.getStatus()), MdmChangeoverRuleEntity::getStatus, query.getStatus())
                .orderByDesc(MdmChangeoverRuleEntity::getValidStartTime)
                .orderByDesc(MdmChangeoverRuleEntity::getCreateTime)));
    }

    @GetMapping("/{id}/logs")
    @PreAuthorize("@pms.hasPermission('mdm_changeoverRule_view')")
    public R<List<MdmChangeoverRuleLogEntity>> logs(@PathVariable Long id) {
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<MdmChangeoverRuleLogEntity>()
                .eq(MdmChangeoverRuleLogEntity::getRuleId, id)
                .orderByDesc(MdmChangeoverRuleLogEntity::getOperatedTime)));
    }

    @PostMapping
    @SysLog("新增APS换型规则草稿")
    @PreAuthorize("@pms.hasPermission('mdm_changeoverRule_edit')")
    public R<Long> save(@RequestBody MdmChangeoverRuleEntity entity) {
        return R.ok(service.saveDraft(entity));
    }

    @PutMapping
    @SysLog("修改APS换型规则草稿")
    @PreAuthorize("@pms.hasPermission('mdm_changeoverRule_edit')")
    public R<Boolean> update(@RequestBody MdmChangeoverRuleEntity entity) {
        return R.ok(service.updateDraft(entity));
    }

    @PutMapping("/status")
    @SysLog("启用、暂停或退役APS换型规则")
    @PreAuthorize("@pms.hasPermission('mdm_changeoverRule_approve')")
    public R<Boolean> status(@RequestBody MdmChangeoverRuleActionDTO action) {
        return R.ok(service.changeStatus(action));
    }

    @PostMapping("/catalog")
    @Inner
    public R<MdmChangeoverCatalogDTO> catalog(@RequestBody MdmChangeoverCatalogQueryDTO query) {
        return R.ok(service.catalog(query));
    }
}
