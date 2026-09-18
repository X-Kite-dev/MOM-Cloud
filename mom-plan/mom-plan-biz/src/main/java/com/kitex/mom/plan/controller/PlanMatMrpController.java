package com.kitex.mom.plan.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlanMrpInventoryPeggingEntity;
import com.kitex.mom.plan.dto.PlanMatSubstituteDecisionDTO;
import com.kitex.mom.plan.dto.PlanMatMrpReconcileEvidenceDTO;
import com.kitex.mom.plan.dto.PlanMatMrpReconcileQueryDTO;
import com.kitex.mom.plan.dto.PlanMatReservationReallocateDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageAnalysisDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageQueryDTO;
import com.kitex.mom.plan.dto.PlanAgentSubstituteDecisionDTO;
import com.kitex.mom.plan.dto.PlanAgentTransferDecisionDTO;
import com.kitex.mom.plan.dto.PlanAgentTransferReceiptDTO;
import com.kitex.mom.plan.dto.PlanAgentPurchaseOrderDTO;
import com.kitex.mom.plan.dto.PlanAgentPurchaseReceiptDTO;
import com.kitex.mom.plan.dto.PlanAgentPurchaseRejectionDTO;
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
                .eq(StrUtil.isNotBlank(planMatMrp.getDemandWarehouseCode()), PlanMatMrpEntity::getDemandWarehouseCode, planMatMrp.getDemandWarehouseCode())
                .eq(StrUtil.isNotBlank(planMatMrp.getType()), PlanMatMrpEntity::getType, planMatMrp.getType())
                .eq(StrUtil.isNotBlank(planMatMrp.getReadinessStatus()), PlanMatMrpEntity::getReadinessStatus, planMatMrp.getReadinessStatus())
                .eq(StrUtil.isNotBlank(planMatMrp.getSubstituteStatus()), PlanMatMrpEntity::getSubstituteStatus, planMatMrp.getSubstituteStatus())
                .eq(Objects.nonNull(planMatMrp.getStatus()), PlanMatMrpEntity::getStatus, planMatMrp.getStatus())
                .orderByDesc(PlanMatMrpEntity::getCreateTime);
        com.baomidou.mybatisplus.core.metadata.IPage<PlanMatMrpEntity> result = planMatMrpService.page(page.toMpPage(), wrapper);
        planMatMrpService.enrichReservationStatuses(result.getRecords());
        return R.ok(result);
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
                .eq(StrUtil.isNotBlank(planMatMrp.getDemandWarehouseCode()), PlanMatMrpEntity::getDemandWarehouseCode, planMatMrp.getDemandWarehouseCode())
                .eq(StrUtil.isNotBlank(planMatMrp.getType()), PlanMatMrpEntity::getType, planMatMrp.getType())
                .eq(StrUtil.isNotBlank(planMatMrp.getReadinessStatus()), PlanMatMrpEntity::getReadinessStatus, planMatMrp.getReadinessStatus())
                .eq(StrUtil.isNotBlank(planMatMrp.getSubstituteStatus()), PlanMatMrpEntity::getSubstituteStatus, planMatMrp.getSubstituteStatus());
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
        PlanMatMrpEntity result = planMatMrpService.getById(id);
        planMatMrpService.enrichReservationStatuses(result == null ? List.of() : List.of(result));
        return R.ok(result);
    }

    @Operation(summary = "查询MRP库存Pegging", description = "按分配顺序返回需求路径到WMS库存行的冻结证据")
    @GetMapping("/{id}/inventory-pegging")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_view')")
    public R<List<PlanMrpInventoryPeggingEntity>> inventoryPegging(@PathVariable("id") Long id) {
        return R.ok(planMatMrpService.inventoryPegging(id));
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

    @Operation(summary = "替代方案决策", description = "批准时重新计算剩余净需求；拒绝后保留原缺料供应建议")
    @SysLog("MRP替代方案决策")
    @PostMapping("/substitute/decision")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_edit')")
    public R<Boolean> decideSubstitute(@RequestBody PlanMatSubstituteDecisionDTO command) {
        return R.ok(planMatMrpService.decideSubstitute(command));
    }

    @Operation(summary = "重配替代料预留批次", description = "仅限未发料且原认证/许可仍有效的风险预留")
    @SysLog("MRP替代料预留批次重配")
    @PostMapping("/reservation/reallocate")
    @PreAuthorize("@pms.hasPermission('plan_planMatMrp_edit')")
    public R<Boolean> reallocateReservation(@RequestBody PlanMatReservationReallocateDTO command) {
        return R.ok(planMatMrpService.reallocateReservation(command));
    }

    @Operation(summary = "获取MRP跨模块对账证据")
    @PostMapping("/reconcile/evidence")
    public R<List<PlanMatMrpReconcileEvidenceDTO>> reconciliationEvidence(@RequestBody PlanMatMrpReconcileQueryDTO query) {
        return R.ok(planMatMrpService.reconciliationEvidence(query == null ? null : query.getSourceIds()));
    }

    @Operation(summary = "Agent缺料分析", description = "只读返回指定工厂、计划或订单范围内的确定性MRP证据")
    @PostMapping("/agent/shortage-analysis")
    @PreAuthorize("@pms.hasPermission('agent:shortage:run')")
    public R<PlanMrpShortageAnalysisDTO> shortageAnalysis(@RequestBody PlanMrpShortageQueryDTO query) {
        return R.ok(planMatMrpService.shortageAnalysis(query));
    }

    @Operation(summary = "执行已审批的Agent替代决策", description = "复核工厂范围后复用MRP替代决策状态机")
    @PostMapping("/agent/substitute-decision")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute') and @pms.hasPermission('plan_planMatMrp_edit')")
    public R<Boolean> agentSubstituteDecision(@RequestBody PlanAgentSubstituteDecisionDTO command) {
        return R.ok(planMatMrpService.agentSubstituteDecision(command));
    }

    @Operation(summary = "执行已审批的Agent跨仓调拨", description = "复核MRP与最新WMS跨仓证据后创建正式在途调拨单")
    @PostMapping("/agent/transfer-decision")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute') and @pms.hasPermission('plan_planMatMrp_edit')")
    public R<PlanAgentTransferReceiptDTO> agentTransferDecision(@RequestBody PlanAgentTransferDecisionDTO command) {
        return R.ok(planMatMrpService.agentTransferDecision(command));
    }

    @Operation(summary = "执行已审批的Agent采购下达", description = "重采MRP/PMS/MDM价格与认证事实后创建采购订单并进入PMS正式审批")
    @PostMapping("/agent/purchase-order")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute') and @pms.hasPermission('plan_planMatMrp_edit')")
    public R<PlanAgentPurchaseReceiptDTO> agentPurchaseOrder(@RequestBody PlanAgentPurchaseOrderDTO command) {
        return R.ok(planMatMrpService.agentPurchaseOrder(command));
    }

    @Inner
    @Operation(summary = "接收PMS Agent采购驳回事实", description = "永久幂等回写MRP；旧订单迟到事件只留SUPERSEDED回执")
    @PostMapping("/internal/agent-purchase/rejection")
    public R<PlanAgentPurchaseRejectionDTO.Receipt> rejectAgentPurchaseOrder(
            @RequestBody PlanAgentPurchaseRejectionDTO.Command command) {
        return R.ok(planMatMrpService.rejectAgentPurchaseOrder(command));
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
