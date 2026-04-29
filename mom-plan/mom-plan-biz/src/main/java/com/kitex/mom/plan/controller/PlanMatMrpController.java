package com.kitex.mom.plan.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.service.PlanMatMrpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MRP运算记录表
 *
 * @author kitex
 * @date 2025-04-15 17:37:58
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/planMatMrp")
@Tag(description = "planMatMrp", name = "MRP运算记录表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlanMatMrpController {

    private final PlanMatMrpService planMatMrpService;

    /**
     * 分页查询
     *
     * @param page       分页对象
     * @param planMatMrp MRP运算记录表
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_view')")
    public R getPlanMatMrpPage(@ParameterObject SearchPage page, @ParameterObject PlanMatMrpEntity planMatMrp) {
        QueryWrapper<PlanMatMrpEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(planMatMrp.getPlanDemandNo()), PlanMatMrpEntity::getPlanDemandNo, planMatMrp.getPlanDemandNo())
                .like(StrUtil.isNotBlank(planMatMrp.getSaleOrderCode()), PlanMatMrpEntity::getSaleOrderCode, planMatMrp.getSaleOrderCode())
                .like(StrUtil.isNotBlank(planMatMrp.getMatCode()), PlanMatMrpEntity::getMatCode, planMatMrp.getMatCode())
                .like(StrUtil.isNotBlank(planMatMrp.getMatName()), PlanMatMrpEntity::getMatName, planMatMrp.getMatName())
                .like(StrUtil.isNotBlank(planMatMrp.getMatSpec()), PlanMatMrpEntity::getMatSpec, planMatMrp.getMatSpec())
                .eq(StrUtil.isNotBlank(planMatMrp.getType()), PlanMatMrpEntity::getType, planMatMrp.getType())
                .eq(Objects.nonNull(planMatMrp.getStatus()), PlanMatMrpEntity::getStatus, planMatMrp.getStatus())
                .orderByDesc(PlanMatMrpEntity::getCreateTime);
        return R.ok(planMatMrpService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各下发状态的数量
     */

    @Operation(summary = "查询各下发状态的数量", description = "查询各下发状态的数量")
    @GetMapping("/getCount")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_view')")
    public R getCount(@ParameterObject PlanMatMrpEntity planMatMrp) {
        QueryWrapper<PlanMatMrpEntity> wrapper = Wrappers.query();
        wrapper.lambda().select(PlanMatMrpEntity::getStatus)
                .like(StrUtil.isNotBlank(planMatMrp.getPlanDemandNo()), PlanMatMrpEntity::getPlanDemandNo, planMatMrp.getPlanDemandNo())
                .like(StrUtil.isNotBlank(planMatMrp.getSaleOrderCode()), PlanMatMrpEntity::getSaleOrderCode, planMatMrp.getSaleOrderCode())
                .like(StrUtil.isNotBlank(planMatMrp.getMatCode()), PlanMatMrpEntity::getMatCode, planMatMrp.getMatCode())
                .like(StrUtil.isNotBlank(planMatMrp.getMatName()), PlanMatMrpEntity::getMatName, planMatMrp.getMatName())
                .like(StrUtil.isNotBlank(planMatMrp.getMatSpec()), PlanMatMrpEntity::getMatSpec, planMatMrp.getMatSpec())
                .eq(StrUtil.isNotBlank(planMatMrp.getType()), PlanMatMrpEntity::getType, planMatMrp.getType());
        return R.ok(planMatMrpService.list(wrapper)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(item -> Objects.requireNonNullElse(item.getStatus(), Boolean.FALSE), Collectors.counting())));
    }

    /**
     * 通过id查询MRP运算记录表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(planMatMrpService.getById(id));
    }

    /**
     * 下发任务
     *
     * @param ids MRP运算记录表ids
     * @return R
     */
    @Operation(summary = "下发任务", description = "下发任务")
    @SysLog("下发任务")
    @PutMapping("/issue")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_edit')")
    public R issueByIds(@RequestBody Long[] ids) {
        return R.ok(planMatMrpService.issueByIds(ids));
    }


    /**
     * 导出excel 表格
     *
     * @param planMatMrp 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_export')")
    public List<PlanMatMrpEntity> export(PlanMatMrpEntity planMatMrp, Long[] ids) {
        return planMatMrpService.list(Wrappers.lambdaQuery(planMatMrp).in(ArrayUtil.isNotEmpty(ids), PlanMatMrpEntity::getId, ids));
    }
}