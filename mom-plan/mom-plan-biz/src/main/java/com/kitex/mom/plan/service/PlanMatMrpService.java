package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlanMrpInventoryPeggingEntity;

import java.util.List;
import java.util.Set;

public interface PlanMatMrpService extends IService<PlanMatMrpEntity> {

    /**
     * 下发任务
     *
     * @param ids MRP运算记录表ids
     * @return R
     */
    Boolean issueByIds(Long[] ids);

    /**
     * 审批或拒绝系统生成的替代方案。
     */
    Boolean decideSubstitute(PlanMatSubstituteDecisionDTO command);

    /**
     * 在未发料前把运行期失效批次迁移到仍符合原批准快照的库存批次。
     */
    Boolean reallocateReservation(PlanMatReservationReallocateDTO command);

    /**
     * 列表和详情展示前批量回查WMS权威预留状态。
     */
    void enrichReservationStatuses(List<PlanMatMrpEntity> records);

    /**
     * 为PLAN-WMS-MES对账提供MRP权威业务状态。
     */
    List<PlanMatMrpReconcileEvidenceDTO> reconciliationEvidence(Set<Long> sourceIds);

    /**
     * Agent只读工具：在明确计划/订单及工厂范围内返回可复算的缺料证据。
     */
    PlanMrpShortageAnalysisDTO shortageAnalysis(PlanMrpShortageQueryDTO query);

    /**
     * Agent人审后的替代决策，先复核工厂范围，再复用原权威决策状态机。
     */
    Boolean agentSubstituteDecision(PlanAgentSubstituteDecisionDTO command);

    PlanAgentTransferReceiptDTO agentTransferDecision(PlanAgentTransferDecisionDTO command);

    /**
     * 审批后复核缺口、在途、供应商认证、价格及提前期，再进入PMS正式订单审批流。
     */
    PlanAgentPurchaseReceiptDTO agentPurchaseOrder(PlanAgentPurchaseOrderDTO command);

    /**
     * PMS正式驳回Agent采购订单后，永久幂等释放该订单在MRP上的有效供应占用。
     */
    PlanAgentPurchaseRejectionDTO.Receipt rejectAgentPurchaseOrder(PlanAgentPurchaseRejectionDTO.Command command);

    /**
     * 查询一条MRP的跨需求、分时段库存来源Pegging证据。
     */
    List<PlanMrpInventoryPeggingEntity> inventoryPegging(Long mrpId);

    /**
     * 在MRP事务内按工厂+需求仓串行库存净分配，防止并发运算重复占用。
     */
    void lockInventoryAllocationPools(Set<String> lockKeys);

    /**
     * 补产撤回时幂等释放主料/替代料预留并把MRP与Pegging改为已释放、未下达。
     */
    Boolean releaseForWithdrawal(Long mrpId, String requestId, String reason);

    /**
     * 冻结MRP生成的下层自制/采购/外协供应来源；历史无精确来源的数据故障关闭。
     */
    PlanMrpSupplyWithdrawalDTO.Evidence previewGeneratedSupplyWithdrawal(Long mrpId);

    /**
     * 执行阶段按申请时的下游证据重建本地冻结事实；不重复预览已可能由前次尝试撤回的远端单据，
     * 真正执行时仍由PMS/MES用该证据指纹做防陈旧校验。
     */
    PlanMrpSupplyWithdrawalDTO.Evidence captureGeneratedSupplyWithdrawalForExecution(
            Long mrpId, String expectedDownstreamEvidenceFingerprint);

    /**
     * 仅撤回未进入执行且无任何完成事实的精确下层供应单据；PMS计划已生成但未形成订单时也可受控撤回，并永久保留幂等回执。
     */
    PlanMrpSupplyWithdrawalDTO.Receipt withdrawGeneratedSupply(PlanMrpSupplyWithdrawalDTO.Command command);

    /**
     * 完成数量处理
     *
     * @param omsOrderLifeCycleDtoList 订单生命周期DTO
     * @return R
     */
    Boolean completeNumHandle(List<OmsOrderLifeCycleDTO> omsOrderLifeCycleDtoList);
}
