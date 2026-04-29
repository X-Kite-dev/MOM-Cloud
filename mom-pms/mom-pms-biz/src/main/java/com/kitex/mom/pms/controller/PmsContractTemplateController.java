package com.kitex.mom.pms.controller;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.entity.PmsContractTemplateEntity;
import com.kitex.mom.pms.service.PmsContractTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 合同模板
 *
 * @author kitex
 * @date 2025-04-02 15:58:17
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsContractTemplate")
@Tag(description = "pmsContractTemplate", name = "合同模板管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsContractTemplateController {

    private final PmsContractTemplateService pmsContractTemplateService;

    /**
     * 分页查询
     *
     * @param page                分页对象
     * @param pmsContractTemplate 合同模板
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsContractTemplate_view')")
    public R getPmsContractTemplatePage(@ParameterObject SearchPage page, @ParameterObject PmsContractTemplateEntity pmsContractTemplate) {
        QueryWrapper<PmsContractTemplateEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsContractTemplate.getCode()), PmsContractTemplateEntity::getCode, pmsContractTemplate.getCode())
                .like(StrUtil.isNotBlank(pmsContractTemplate.getName()), PmsContractTemplateEntity::getName, pmsContractTemplate.getName())
                .eq(StrUtil.isNotBlank(pmsContractTemplate.getServiceType()), PmsContractTemplateEntity::getServiceType, pmsContractTemplate.getServiceType())
                .like(StrUtil.isNotBlank(pmsContractTemplate.getContractType()), PmsContractTemplateEntity::getContractType, pmsContractTemplate.getContractType())
                .orderByDesc(PmsContractTemplateEntity::getCreateTime);
        return R.ok(pmsContractTemplateService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询合同模板信息
     *
     * @param query
     * @return R
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询")
    @GetMapping("/detail")
    public R getById(@ParameterObject PmsContractTemplateEntity query) {
        return R.ok(pmsContractTemplateService.getOne(Wrappers.query(query), false));
    }

    /**
     * 通过id查询合同模板
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('pms_pmsContractTemplate_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(pmsContractTemplateService.getById(id));
    }

    /**
     * 新增合同模板
     *
     * @param pmsContractTemplate 合同模板
     * @return R
     */
    @Operation(summary = "新增合同模板", description = "新增合同模板")
    @SysLog("新增合同模板")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsContractTemplate_add')")
    public R save(@RequestBody PmsContractTemplateEntity pmsContractTemplate) {
        return R.ok(pmsContractTemplateService.save(pmsContractTemplate));
    }

    /**
     * 修改合同模板
     *
     * @param pmsContractTemplate 合同模板
     * @return R
     */
    @Operation(summary = "修改合同模板", description = "修改合同模板")
    @SysLog("修改合同模板")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsContractTemplate_edit')")
    public R updateById(@RequestBody PmsContractTemplateEntity pmsContractTemplate) {
        return R.ok(pmsContractTemplateService.updateById(pmsContractTemplate));
    }

    /**
     * 通过id删除合同模板
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除合同模板", description = "通过id删除合同模板")
    @SysLog("通过id删除合同模板")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsContractTemplate_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsContractTemplateService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param pmsContractTemplate 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsContractTemplate_export')")
    public List<PmsContractTemplateEntity> export(PmsContractTemplateEntity pmsContractTemplate, Long[] ids) {
        return pmsContractTemplateService.list(Wrappers.lambdaQuery(pmsContractTemplate).in(ArrayUtil.isNotEmpty(ids), PmsContractTemplateEntity::getId, ids));
    }
}