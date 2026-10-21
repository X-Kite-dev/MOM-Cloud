package com.kitex.mom.pms.agent.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.agent.dto.PmsProcurementRiskDTO;
import com.kitex.mom.pms.agent.mapper.PmsAgentProcurementRiskMapper;
import com.kitex.mom.pms.agent.model.PmsProcurementRiskRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 汇总活动采购订单的未到货量、供应商正式承诺覆盖、逾期承诺和近期到货风险。
 * 数量保留订单行单位，不对不同物料单位做虚假合计。
 */
@Service
@RequiredArgsConstructor
public class PmsProcurementRiskQueryService {

    public static final String TOOL_CODE = "PMS_PROCUREMENT_RISK_QUERY";

    private final PmsAgentFactoryAccessService factoryAccessService;
    private final PmsAgentProcurementRiskMapper procurementRiskMapper;

    public PmsProcurementRiskDTO.Evidence query(PmsProcurementRiskDTO.Query query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())) {
            throw new CheckedException("PMS采购风险查询必须指定工厂");
        }
        int horizonDays = query.getHorizonDays() == null ? 7 : query.getHorizonDays();
        int limit = query.getLimit() == null ? 20 : query.getLimit();
        if (horizonDays < 1 || horizonDays > 90 || limit < 1 || limit > 50) {
            throw new CheckedException("PMS采购风险查询阈值超出允许范围");
        }

        PmsAgentFactoryAccessService.Scope scope = factoryAccessService.requireRead(query.getFactoryCode());
        LocalDateTime observedAt = LocalDateTime.now();
        LocalDateTime horizonEnd = observedAt.plusDays(horizonDays);
        PmsProcurementRiskRow.Summary summaryRow = procurementRiskMapper.selectSummary(
                scope.tenantId(), scope.factoryCode(), observedAt, horizonEnd);
        List<PmsProcurementRiskRow.RiskLine> collected = procurementRiskMapper.selectRiskLines(
                scope.tenantId(), scope.factoryCode(), observedAt, horizonEnd, limit + 1);

        return new PmsProcurementRiskDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("活动采购订单指审核通过、收货中或部分到货且仍有未到货量的订单；正式供应商承诺按承诺时刻先后扣减承诺后到货量，逾期与近期到货只统计尚未履约的承诺数量；无正式承诺覆盖的剩余量单独披露")
                .setFactoryCode(scope.factoryCode()).setObservedAt(observedAt).setHorizonEnd(horizonEnd)
                .setSummary(mapSummary(summaryRow))
                .setRiskLines(collected.stream().limit(limit).map(this::mapRiskLine).toList())
                .setTruncated(collected.size() > limit)
                .setDataGaps(List.of(
                        "采购数量按订单行物料单位保存，没有统一单位换算时禁止跨物料合计数量",
                        "订单内部要货日只用于辅助识别目标日期风险，不能替代供应商正式承诺时刻",
                        "本场景反映订单、到货与正式承诺事实，不推断运输在途位置、供应商产能或外部物流ETA",
                        "待审批订单只统计主单数量；是否影响生产仍需结合PLAN齐套与MRP需求证据"))
                .setEvidenceRefs(List.of(
                        "mom_pms.pms_order(id,pms_order_no,status,supplier_code,factory_code)",
                        "mom_pms.pms_order_detail(id,pms_order_id,mat_code,mat_unit,order_num,arrival_num,cancelled_num,delivery_time)",
                        "mom_pms.pms_delivery_commitment(id,order_detail_id,status,arrival_num_baseline)",
                        "mom_pms.pms_delivery_commitment_item(commitment_id,committed_num,committed_delivery_time)"));
    }

    private PmsProcurementRiskDTO.Summary mapSummary(PmsProcurementRiskRow.Summary row) {
        if (row == null) {
            return new PmsProcurementRiskDTO.Summary();
        }
        return new PmsProcurementRiskDTO.Summary()
                .setActiveOrderCount(row.getActiveOrderCount()).setOpenLineCount(row.getOpenLineCount())
                .setPendingApprovalOrderCount(row.getPendingApprovalOrderCount())
                .setOverdueCommitmentLineCount(row.getOverdueCommitmentLineCount())
                .setDueSoonLineCount(row.getDueSoonLineCount())
                .setUncommittedGapLineCount(row.getUncommittedGapLineCount())
                .setInternalDueDatePassedLineCount(row.getInternalDueDatePassedLineCount())
                .setCommitmentExcessLineCount(row.getCommitmentExcessLineCount())
                .setMissingUnitLineCount(row.getMissingUnitLineCount());
    }

    private PmsProcurementRiskDTO.RiskLine mapRiskLine(PmsProcurementRiskRow.RiskLine row) {
        List<String> riskTypes = StrUtil.isBlank(row.getRiskCodes()) ? List.of()
                : Arrays.stream(row.getRiskCodes().split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
        return new PmsProcurementRiskDTO.RiskLine()
                .setOrderId(row.getOrderId()).setOrderDetailId(row.getOrderDetailId())
                .setOrderNo(row.getOrderNo()).setOrderStatus(row.getOrderStatus())
                .setSupplierCode(row.getSupplierCode()).setSupplierName(row.getSupplierName())
                .setMaterialCode(row.getMaterialCode()).setMaterialName(row.getMaterialName())
                .setMaterialUnit(row.getMaterialUnit()).setOrderNum(row.getOrderNum())
                .setArrivalNum(row.getArrivalNum()).setCancelledNum(row.getCancelledNum())
                .setOutstandingNum(row.getOutstandingNum())
                .setCommittedRemainingNum(row.getCommittedRemainingNum())
                .setOverdueCommitmentNum(row.getOverdueCommitmentNum())
                .setDueSoonCommitmentNum(row.getDueSoonCommitmentNum())
                .setUncommittedGapNum(row.getUncommittedGapNum())
                .setInternalDeliveryDate(row.getInternalDeliveryDate())
                .setNextCommittedDeliveryTime(row.getNextCommittedDeliveryTime())
                .setRiskTypes(riskTypes);
    }
}
