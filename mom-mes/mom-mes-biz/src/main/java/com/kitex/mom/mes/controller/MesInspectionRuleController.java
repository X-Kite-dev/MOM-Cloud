package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesInspectionRuleEntity;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

/**
 * 质检任务生成规则
 *
 * @author kitex
 * @since 2024-02-06
 */
@RestController
@AllArgsConstructor
@RequestMapping("/mesInspectionRule")
@Tag(name = "质检任务生成规则管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesInspectionRuleController {

    private final MesInspectionRuleService mesInspectionRuleService;

    /**
     * 分页查询
     *
     * @param page  分页对象
     * @param query 质检任务生成规则
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesInspectionRule:show')")
    public R<IPage<MesInspectionRuleEntity>> getMesInspectionRulePage(Page<MesInspectionRuleEntity> page, MesInspectionRuleEntity query) {
        QueryWrapper<MesInspectionRuleEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda().orderByDesc(MesInspectionRuleEntity::getCreateTime);
        return R.ok(mesInspectionRuleService.page(page, autoQueryWrapper));
    }

    /**
     * 通过id查询质检任务生成规则
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mesInspectionRuleService.detailById(id));
    }

    /**
     * 新增质检任务生成规则
     *
     * @param mesInspectionRule 质检任务生成规则
     * @return R
     */
    @Operation(summary = "新增质检任务生成规则", description = "新增质检任务生成规则")
    @SysLog("新增质检任务生成规则")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mesInspectionRule:add')")
    public R save(@RequestBody MesInspectionRuleEntity mesInspectionRule) {
        mesInspectionRuleService.saveData(mesInspectionRule);
        return R.ok();
    }

    /**
     * 修改质检任务生成规则
     *
     * @param mesInspectionRule 质检任务生成规则
     * @return R
     */
    @Operation(summary = "修改质检任务生成规则", description = "修改质检任务生成规则")
    @SysLog("修改质检任务生成规则")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mesInspectionRule:add')")
    public R updateById(@RequestBody MesInspectionRuleEntity mesInspectionRule) {
        mesInspectionRuleService.saveData(mesInspectionRule);
        return R.ok();
    }

    @Operation(summary = "通过id删除", description = "通过id删除")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesInspectionRule:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        mesInspectionRuleService.removeData(Arrays.asList(ids));
        return R.ok();
    }

    @Operation(summary = "根据物料编码查询质检任务生成规则", description = "根据物料编码查询质检任务生成规则")
    @GetMapping("/getRuleByMatCode")
    public R<MesInspectionRuleEntity> getRuleByMatCode(@RequestParam("matCode") String matCode) {
        return R.ok(mesInspectionRuleService.getRuleByMatCode(matCode));
    }
}
