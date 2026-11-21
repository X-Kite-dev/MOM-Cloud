package com.kitex.mom.pms.agent.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.agent.dto.PmsProcurementRiskDTO;
import com.kitex.mom.pms.agent.mapper.PmsAgentProcurementRiskMapper;
import com.kitex.mom.pms.agent.model.PmsProcurementRiskRow;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

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
    private final PmsOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

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
        PmsOrderEntity selectedOrder = selectedOrder(query, scope);
        Long orderId = selectedOrder == null ? null : selectedOrder.getId();
        String orderNo = selectedOrder == null ? null : selectedOrder.getPmsOrderNo();
        LocalDateTime observedAt = LocalDateTime.now();
        LocalDateTime horizonEnd = observedAt.plusDays(horizonDays);
        PmsProcurementRiskRow.Summary summaryRow = procurementRiskMapper.selectSummary(
                scope.tenantId(), scope.factoryCode(), orderId, orderNo, observedAt, horizonEnd);
        List<PmsProcurementRiskRow.RiskLine> collected = procurementRiskMapper.selectRiskLines(
                scope.tenantId(), scope.factoryCode(), orderId, orderNo, observedAt, horizonEnd, limit + 1);

        PmsProcurementRiskDTO.Evidence evidence = new PmsProcurementRiskDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("活动采购订单指审核通过、收货中或部分到货且仍有未到货量的订单；正式供应商承诺按承诺时刻先后扣减承诺后到货量，逾期与近期到货只统计尚未履约的承诺数量；无正式承诺覆盖的剩余量单独披露")
                .setFactoryCode(scope.factoryCode()).setOrderScoped(selectedOrder != null)
                .setOrderId(selectedOrder == null ? null : String.valueOf(selectedOrder.getId()))
                .setOrderNo(orderNo).setOrderStatus(selectedOrder == null ? null : selectedOrder.getStatus())
                .setObservedAt(observedAt).setHorizonEnd(horizonEnd)
                .setSummary(mapSummary(summaryRow))
                .setRiskLines(collected.stream().limit(limit).map(this::mapRiskLine).toList())
                .setTruncated(collected.size() > limit)
                .setLimitations(List.of(
                        "采购数量按订单行物料单位保存，没有统一单位换算时禁止跨物料合计数量",
                        "订单内部要货日只用于辅助识别目标日期风险，不能替代供应商正式承诺时刻",
                        "本场景反映订单、到货与正式承诺事实，不推断运输在途位置、供应商产能或外部物流ETA",
                        "待审批订单只统计主单数量；是否影响生产仍需结合PLAN齐套与MRP需求证据"))
                .setDataGaps(dataGaps(summaryRow, collected.size() > limit))
                .setEvidenceRefs(selectedOrder == null ? List.of(
                        "mom_pms.pms_order(id,pms_order_no,status,supplier_code,factory_code)",
                        "mom_pms.pms_order_detail(id,pms_order_id,mat_code,mat_unit,order_num,arrival_num,cancelled_num,delivery_time)",
                        "mom_pms.pms_delivery_commitment(id,order_detail_id,status,arrival_num_baseline)",
                        "mom_pms.pms_delivery_commitment_item(commitment_id,committed_num,committed_delivery_time)")
                        : List.of("PMS:pms_order:" + selectedOrder.getId(),
                        "PMS:pms_order_detail:order:" + selectedOrder.getId(),
                        "PMS:pms_delivery_commitment:order:" + selectedOrder.getId()));
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    /** 浏览器只提供候选身份；PMS必须按当前租户、工厂重新确认唯一订单。 */
    private PmsOrderEntity selectedOrder(PmsProcurementRiskDTO.Query query,
                                         PmsAgentFactoryAccessService.Scope scope) {
        boolean byId = StrUtil.isNotBlank(query.getOrderId());
        boolean byNo = StrUtil.isNotBlank(query.getOrderNo());
        if (!byId && !byNo) return null;
        String requestedOrderNo = byNo ? query.getOrderNo().trim() : null;
        Long id = null;
        if (byId) {
            try {
                id = Long.valueOf(query.getOrderId());
            } catch (NumberFormatException overflow) {
                throw new CheckedException("采购订单主键超出数据库范围");
            }
        }
        var condition = new LambdaQueryWrapper<PmsOrderEntity>()
                .eq(PmsOrderEntity::getTenantId, scope.tenantId())
                .eq(PmsOrderEntity::getFactoryCode, scope.factoryCode())
                .eq(id != null, PmsOrderEntity::getId, id)
                .eq(byNo, PmsOrderEntity::getPmsOrderNo, requestedOrderNo)
                .orderByAsc(PmsOrderEntity::getId).last("LIMIT 2");
        List<PmsOrderEntity> matches = orderMapper.selectList(condition);
        if (matches.size() != 1)
            throw new CheckedException(matches.isEmpty()
                    ? "当前租户及工厂没有该采购订单" : "采购订单号在当前工厂不唯一，不能用于Agent取证");
        PmsOrderEntity order = matches.get(0);
        if (byId && !Objects.equals(query.getOrderId(), String.valueOf(order.getId())))
            throw new CheckedException("采购订单主键与PMS权威订单不一致");
        if (byNo && !Objects.equals(requestedOrderNo, order.getPmsOrderNo()))
            throw new CheckedException("采购订单号与PMS权威订单不一致");
        return order;
    }

    /** 只把会妨碍当前证据完整性的事实放入缺口，固定统计边界另列为limitations。 */
    private List<String> dataGaps(PmsProcurementRiskRow.Summary summary, boolean truncated) {
        List<String> gaps = new ArrayList<>();
        if (summary != null && summary.getMissingUnitLineCount() != null
                && summary.getMissingUnitLineCount() > 0) {
            gaps.add("存在" + summary.getMissingUnitLineCount() + "条活动采购明细缺少物料单位，数量仅可逐行核对");
        }
        if (summary != null && summary.getCommitmentExcessLineCount() != null
                && summary.getCommitmentExcessLineCount() > 0) {
            gaps.add("存在" + summary.getCommitmentExcessLineCount() + "条供应商正式承诺剩余量超过订单未到货量，需PMS人工核对承诺版本");
        }
        if (truncated) gaps.add("风险明细超过本轮返回上限，当前证据不是完整风险清单");
        return gaps;
    }

    private String fingerprint(PmsProcurementRiskDTO.Evidence evidence) {
        try {
            String facts = objectMapper.writeValueAsString(List.of(evidence.getFactoryCode(),
                    evidence.getOrderScoped(), Objects.toString(evidence.getOrderId(), ""),
                    Objects.toString(evidence.getOrderNo(), ""), Objects.toString(evidence.getOrderStatus(), ""),
                    evidence.getObservedAt(), evidence.getHorizonEnd(), evidence.getSummary(),
                    evidence.getRiskLines(), evidence.getLimitations(), evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) {
            throw new CheckedException("采购履约风险事实无法计算完整指纹");
        }
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
                .setPurchasePlanNo(row.getPurchasePlanNo())
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
