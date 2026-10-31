package com.kitex.mom.pms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.agent.dto.PmsIncomingAvailabilityDTO;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptBatchDetailEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptEntity;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsPlanMapper;
import com.kitex.mom.pms.mapper.WmsPurchaseReceiptBatchDetailMapper;
import com.kitex.mom.pms.mapper.WmsPurchaseReceiptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按一张真实采购收料单冻结PMS来源，不按物料或供应商模糊归并。
 */
@Service
@RequiredArgsConstructor
public class PmsIncomingAvailabilityQueryService {

    public static final String TOOL_CODE = "PMS_INCOMING_AVAILABILITY_SOURCE_QUERY@1";

    private final PmsAgentFactoryAccessService factoryAccess;
    private final WmsPurchaseReceiptMapper receiptMapper;
    private final WmsPurchaseReceiptBatchDetailMapper batchMapper;
    private final PmsOrderDetailMapper orderDetailMapper;
    private final PmsPlanMapper planMapper;
    private final ObjectMapper objectMapper;

    public PmsIncomingAvailabilityDTO.Evidence query(PmsIncomingAvailabilityDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getReceiptId())) {
            throw new CheckedException("来料可用调查必须指定采购收料单主键");
        }
        PmsAgentFactoryAccessService.Scope scope = factoryAccess.requireRead(query.getFactoryCode());
        Long receiptId = positiveId(query.getReceiptId(), "采购收料单主键无效");
        List<WmsPurchaseReceiptEntity> receipts = receiptMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptEntity>()
                        .eq(WmsPurchaseReceiptEntity::getTenantId, scope.tenantId())
                        .eq(WmsPurchaseReceiptEntity::getFactoryCode, scope.factoryCode())
                        .eq(WmsPurchaseReceiptEntity::getId, receiptId)
                        .last("LIMIT 2"));
        if (receipts.size() != 1) {
            throw new CheckedException(receipts.isEmpty()
                    ? "当前租户及工厂没有该采购收料单" : "采购收料单主键不唯一，禁止Agent取证");
        }
        WmsPurchaseReceiptEntity receipt = receipts.get(0);
        List<WmsPurchaseReceiptBatchDetailEntity> batches = batchMapper.selectList(
                new LambdaQueryWrapper<WmsPurchaseReceiptBatchDetailEntity>()
                        .eq(WmsPurchaseReceiptBatchDetailEntity::getTenantId, scope.tenantId())
                        .eq(WmsPurchaseReceiptBatchDetailEntity::getBillId, receiptId)
                        .orderByAsc(WmsPurchaseReceiptBatchDetailEntity::getId));
        List<Long> orderDetailIds = batches.stream().map(WmsPurchaseReceiptBatchDetailEntity::getOrderDetailId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PmsOrderDetailEntity> orderDetails = orderDetailIds.isEmpty() ? Map.of()
                : orderDetailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                        .eq(PmsOrderDetailEntity::getTenantId, scope.tenantId())
                        .eq(PmsOrderDetailEntity::getFactoryCode, scope.factoryCode())
                        .eq(PmsOrderDetailEntity::getDelFlag, "0")
                        .in(PmsOrderDetailEntity::getId, orderDetailIds)).stream()
                .collect(Collectors.toMap(PmsOrderDetailEntity::getId, Function.identity()));
        List<String> purchasePlanNos = orderDetails.values().stream().map(PmsOrderDetailEntity::getPmsPlanNo)
                .filter(StringUtils::hasText).distinct().toList();
        Map<String, List<PmsPlanEntity>> plans = purchasePlanNos.isEmpty() ? Map.of()
                : planMapper.selectList(new LambdaQueryWrapper<PmsPlanEntity>()
                        .eq(PmsPlanEntity::getTenantId, scope.tenantId())
                        .eq(PmsPlanEntity::getFactoryCode, scope.factoryCode())
                        .eq(PmsPlanEntity::getDelFlag, "0")
                        .in(PmsPlanEntity::getPmsPlanNo, purchasePlanNos)).stream()
                .collect(Collectors.groupingBy(item -> planKey(item.getPmsPlanNo(), item.getMatCode())));
        List<PmsIncomingAvailabilityDTO.BatchLine> lines = batches.stream()
                .map(item -> {
                    PmsOrderDetailEntity detail = orderDetails.get(item.getOrderDetailId());
                    List<PmsPlanEntity> candidates = detail == null ? List.of()
                            : plans.getOrDefault(planKey(detail.getPmsPlanNo(), detail.getMatCode()), List.of());
                    return line(item, detail, candidates.size() == 1 ? candidates.get(0) : null);
                }).toList();
        List<String> qualityTaskIds = batches.stream().map(WmsPurchaseReceiptBatchDetailEntity::getQualityId)
                .filter(Objects::nonNull).map(String::valueOf).distinct().toList();
        List<String> gaps = gaps(receipt, batches, orderDetails, plans);
        List<String> refs = new ArrayList<>();
        refs.add("PMS:wms_purchase_receipt:" + receiptId);
        batches.forEach(item -> refs.add("PMS:wms_purchase_receipt_batch_detail:" + item.getId()));
        PmsIncomingAvailabilityDTO.Evidence evidence = new PmsIncomingAvailabilityDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("按采购收料单主键读取PMS收料主单及批次明细；收料、判定和已入库数量保持原物料单位，不跨单位合计，也不把PMS状态代替QMS检验或WMS正式入库回执。")
                .setFactoryCode(scope.factoryCode()).setReceiptId(String.valueOf(receiptId))
                .setReceiptNo(receipt.getBillNo()).setPurchaseOrderNo(receipt.getPmsOrderNo())
                .setSupplierCode(receipt.getSupplierCode()).setSupplierName(receipt.getSupplierName())
                .setWarehouseCode(receipt.getWarehouseCode()).setWarehouseName(receipt.getWarehouseName())
                .setReceiptState(receipt.getState()).setObservedAt(LocalDateTime.now())
                .setBatches(lines).setQualityTaskIds(qualityTaskIds).setDataGaps(gaps)
                .setLimitations(List.of("PMS收料状态只证明采购业务推进，不证明QMS已放行或WMS已形成可用库存",
                        "同一收料单可包含多个物料和单位，数量只能逐批次对账"))
                .setEvidenceRefs(List.copyOf(refs));
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private PmsIncomingAvailabilityDTO.BatchLine line(WmsPurchaseReceiptBatchDetailEntity item,
                                                       PmsOrderDetailEntity orderDetail,
                                                       PmsPlanEntity plan) {
        return new PmsIncomingAvailabilityDTO.BatchLine()
                .setReceiptBatchId(String.valueOf(item.getId()))
                .setPurchaseOrderDetailId(text(item.getOrderDetailId()))
                .setPurchasePlanNo(orderDetail == null ? null : orderDetail.getPmsPlanNo())
                .setPlanSourceIssueMonthId(plan == null ? null : text(plan.getSourceIssueMonthId()))
                .setPlanSourceReferenceNo(plan == null ? null : plan.getOrderNo())
                .setMaterialCode(item.getMatCode()).setMaterialName(item.getMatName())
                .setMaterialSpec(item.getMatSpec()).setMaterialBatch(item.getMatBatch())
                .setMaterialUnit(item.getMatUnit()).setReceivedQuantity(decimal(item.getMatNum()))
                .setInspectedQuantity(decimal(item.getQualityNum())).setInboundQuantity(decimal(item.getInNum()))
                .setReceiptLineState(item.getState()).setQualityTaskId(text(item.getQualityId()))
                .setQualityResult(item.getQualityResult())
                .setQualifiedNoticeGenerated(flag(item.getQualifiedGen()))
                .setUnqualifiedNoticeGenerated(flag(item.getUnQualifiedGen()));
    }

    private List<String> gaps(WmsPurchaseReceiptEntity receipt,
                              List<WmsPurchaseReceiptBatchDetailEntity> batches,
                              Map<Long, PmsOrderDetailEntity> orderDetails,
                              Map<String, List<PmsPlanEntity>> plans) {
        List<String> gaps = new ArrayList<>();
        if (!StringUtils.hasText(receipt.getBillNo()) || !StringUtils.hasText(receipt.getPmsOrderNo())) {
            gaps.add("采购收料主单缺少收料单号或采购订单号");
        }
        if (!StringUtils.hasText(receipt.getSupplierCode()) || !StringUtils.hasText(receipt.getWarehouseCode())) {
            gaps.add("采购收料主单缺少供应商或目标仓库，禁止跨域确认来料归属");
        }
        if (batches.isEmpty()) {
            gaps.add("采购收料单尚无现场收料批次，不能进入来料可用核验");
        }
        for (WmsPurchaseReceiptBatchDetailEntity item : batches) {
            String label = Objects.toString(item.getId(), "UNKNOWN");
            if (item.getOrderDetailId() == null || !StringUtils.hasText(item.getMatCode())
                    || !StringUtils.hasText(item.getMatBatch())) {
                gaps.add("收料批次" + label + "缺少采购订单行、物料或批次来源");
            }
            PmsOrderDetailEntity orderDetail = orderDetails.get(item.getOrderDetailId());
            if (orderDetail == null || !StringUtils.hasText(orderDetail.getPmsPlanNo())) {
                gaps.add("收料批次" + label + "无法追溯到唯一采购订单行和采购计划");
            } else if (!Objects.equals(item.getMatCode(), orderDetail.getMatCode())) {
                gaps.add("收料批次" + label + "物料与采购订单行不一致");
            }
            if (orderDetail != null && StringUtils.hasText(orderDetail.getPmsPlanNo())) {
                List<PmsPlanEntity> candidates = plans.getOrDefault(
                        planKey(orderDetail.getPmsPlanNo(), orderDetail.getMatCode()), List.of());
                if (candidates.size() != 1) {
                    gaps.add("收料批次" + label + "无法按采购计划号和物料唯一解析采购计划来源");
                } else if (candidates.get(0).getSourceIssueMonthId() == null
                        && !StringUtils.hasText(candidates.get(0).getOrderNo())) {
                    gaps.add("收料批次" + label + "采购计划缺少PLAN来源引用");
                }
            }
            if (!StringUtils.hasText(item.getMatUnit())) {
                gaps.add("收料批次" + label + "缺少物料单位，禁止数量合并");
            }
            BigDecimal received = decimal(item.getMatNum());
            BigDecimal inspected = decimal(item.getQualityNum());
            BigDecimal inbound = decimal(item.getInNum());
            if (received.signum() <= 0) {
                gaps.add("收料批次" + label + "没有正数实收数量");
            }
            if (inspected.signum() < 0 || inbound.signum() < 0
                    || inspected.compareTo(received) > 0 || inbound.compareTo(received) > 0) {
                gaps.add("收料批次" + label + "判定数量或已入库数量超出实收数量边界");
            }
            if (!"EXEMPTION".equals(item.getQualityResult()) && item.getQualityId() == null) {
                gaps.add("收料批次" + label + "既非免检又缺少QMS任务主键");
            }
        }
        return gaps;
    }

    private String planKey(String purchasePlanNo, String materialCode) {
        return Objects.toString(purchasePlanNo, "") + "\u0001" + Objects.toString(materialCode, "");
    }

    private String fingerprint(PmsIncomingAvailabilityDTO.Evidence evidence) {
        try {
            String facts = objectMapper.writeValueAsString(List.of(evidence.getFactoryCode(), evidence.getReceiptId(),
                    Objects.toString(evidence.getReceiptNo(), ""), Objects.toString(evidence.getReceiptState(), ""),
                    evidence.getBatches(), evidence.getQualityTaskIds(), evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("采购收料来源事实无法生成完整指纹");
        }
    }

    private Long positiveId(String value, String message) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new CheckedException(message);
        }
    }

    private BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean flag(String value) {
        if (!StringUtils.hasText(value)) return null;
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
