package com.kitex.mom.plan.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.agent.dto.PlanIncomingAvailabilityImpactDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageAnalysisDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageLineDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageQueryDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnIssueMonthEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlnIssueMonthMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import com.kitex.mom.plan.service.PlanMatMrpService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 复用PLAN既有MRP缺料口径，核对采购来料对原产品需求齐套状态的当前影响。
 *
 * <p>本服务只读产品需求和MRP，不更新缺料、预留或排程，也不把“已到货”直接解释成“已齐套”。</p>
 */
@Service
@RequiredArgsConstructor
public class PlanIncomingAvailabilityImpactQueryService {

    public static final String TOOL_CODE = "PLAN_INCOMING_AVAILABILITY_IMPACT_QUERY@1";
    private static final int MAX_DEMANDS = 50;

    private final PlanAgentActualScopeAccess scopeAccess;
    private final PlnProductDemandMapper demandMapper;
    private final PlnIssueMonthMapper issueMonthMapper;
    private final PlanMatMrpService mrpService;
    private final ObjectMapper objectMapper;

    public PlanIncomingAvailabilityImpactDTO.Evidence query(PlanIncomingAvailabilityImpactDTO.Query query) {
        if (query == null || query.getSources() == null || query.getSources().isEmpty()
                || query.getSources().size() > MAX_DEMANDS) {
            throw new CheckedException("来料齐套影响查询必须提供1至" + MAX_DEMANDS + "个采购来源引用");
        }
        scopeAccess.require(query.getFactoryCode(), "read");
        Long tenantId = TenantContextHolder.getTenantId();
        LinkedHashSet<String> sourceKeys = new LinkedHashSet<>();
        for (PlanIncomingAvailabilityImpactDTO.SourceReference source : query.getSources()) {
            if (!validSource(source) || !sourceKeys.add(sourceKey(source))) {
                throw new CheckedException("采购来源引用包含重复、非法主键、空值、控制字符或超过100字");
            }
        }

        List<String> gaps = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        Map<String, ResolvedDemand> resolved = new LinkedHashMap<>();
        for (PlanIncomingAvailabilityImpactDTO.SourceReference source : query.getSources()) {
            resolveDemand(source, tenantId, query.getFactoryCode(), gaps, refs)
                    .ifPresent(value -> resolved.computeIfAbsent(value.demandNo(), ResolvedDemand::new)
                            .add(source));
        }

        List<PlanIncomingAvailabilityImpactDTO.DemandImpact> impacts = new ArrayList<>();
        for (ResolvedDemand resolvedDemand : resolved.values()) {
            String demandNo = resolvedDemand.demandNo();
            List<PlnProductDemandEntity> matches = demandMapper.selectList(
                    new LambdaQueryWrapper<PlnProductDemandEntity>()
                            .eq(PlnProductDemandEntity::getTenantId, tenantId)
                            .eq(PlnProductDemandEntity::getFactoryCode, query.getFactoryCode())
                            .eq(PlnProductDemandEntity::getDemandNo, demandNo)
                            .eq(PlnProductDemandEntity::getDelFlag, "0")
                            .last("LIMIT 2"));
            if (matches.size() != 1) {
                gaps.add(matches.isEmpty() ? "PLAN不存在产品需求" + demandNo
                        : "PLAN产品需求号" + demandNo + "不唯一，禁止推断齐套归属");
                continue;
            }
            PlnProductDemandEntity demand = matches.get(0);
            try {
                PlanMrpShortageQueryDTO shortageQuery = new PlanMrpShortageQueryDTO();
                shortageQuery.setFactoryCode(query.getFactoryCode());
                shortageQuery.setPlanDemandId(demand.getId());
                shortageQuery.setPlanDemandNo(demand.getDemandNo());
                shortageQuery.setSaleOrderCode(demand.getSaleOrderCode());
                shortageQuery.setSupplyEvidenceVersion(9);
                PlanMrpShortageAnalysisDTO shortage = mrpService.shortageAnalysis(shortageQuery);
                impacts.add(impact(shortage, resolvedDemand, gaps));
                refs.add("PLAN:pln_product_demand:" + demand.getId());
                shortage.getLines().forEach(line -> refs.add("PLAN:plan_mat_mrp:" + line.getMrpId()));
            } catch (RuntimeException failure) {
                gaps.add("PLAN需求" + demandNo + "无法按当前MRP口径完成齐套复核");
            }
        }
        PlanIncomingAvailabilityImpactDTO.Evidence evidence = new PlanIncomingAvailabilityImpactDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("由PMS提供采购订单行、采购计划和PLAN月计划来源主键；PLAN优先按采购订单行正式回执、否则按月计划来源反向验证产品需求，再复用PLAN_MRP_SHORTAGE_QUERY@9读取齐套、预留、供应和工序影响，不写回MRP或排程。")
                .setFactoryCode(query.getFactoryCode()).setObservedAt(LocalDateTime.now())
                .setSourceCount(query.getSources().size())
                .setResolvedSourceCount(resolved.values().stream().mapToInt(value -> value.orderDetailIds.size()).sum())
                .setDemands(impacts).setDataGaps(gaps)
                .setLimitations(List.of("采购来料完成只改变供应事实；PLAN齐套仍以库存分配、正式预留、需求仓和工序投料时刻为准",
                        "本查询不触发MRP重跑、库存重分配或排程发布"))
                .setEvidenceRefs(refs.stream().distinct().toList());
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    /** 需求级齐套沿用 MRP。缺少原始余量时保留已知欠料，但不能证明整单已齐套或齐套时间。 */
    private PlanIncomingAvailabilityImpactDTO.DemandImpact impact(PlanMrpShortageAnalysisDTO source,
                                                                  ResolvedDemand lineage, List<String> gaps) {
        List<PlanMrpShortageLineDTO> lines = source.getLines();
        Set<String> missingMaterials = new LinkedHashSet<>(lineage.materialCodes);
        lines.forEach(line -> missingMaterials.remove(line.getMaterialCode()));
        if (!missingMaterials.isEmpty()) {
            gaps.add("PLAN需求" + lineage.demandNo() + "未找到收料物料的MRP行：" + String.join("、", missingMaterials));
        }
        boolean incomplete = lines.isEmpty() || lines.stream().anyMatch(line ->
                line.getRecordedRemainingDemandNum() == null || line.getRecordedRemainingDemandNum().signum() < 0);
        String readiness = source.getMaterialAvailabilityStatus();
        if (incomplete) {
            gaps.add("PLAN需求" + lineage.demandNo() + "缺少可核对的MRP行或剩余需求量");
            if (!"BLOCKED".equals(readiness)) {
                readiness = "UNKNOWN";
            }
        }
        List<PlanMrpShortageLineDTO> relevant = lines.stream()
                .filter(line -> lineage.materialCodes.contains(line.getMaterialCode())
                        || line.getRecordedRemainingDemandNum() == null
                        || line.getRecordedRemainingDemandNum().signum() != 0)
                .sorted(Comparator.comparing((PlanMrpShortageLineDTO line) ->
                                !lineage.materialCodes.contains(line.getMaterialCode()))
                        .thenComparing(PlanMrpShortageLineDTO::getMrpId))
                .toList();
        return new PlanIncomingAvailabilityImpactDTO.DemandImpact()
                .setPlanDemandId(String.valueOf(source.getPlanDemandId()))
                .setPlanDemandNo(source.getPlanDemandNo()).setSaleOrderCode(source.getSaleOrderCode())
                .setPurchaseOrderDetailIds(List.copyOf(lineage.orderDetailIds))
                .setPurchasePlanNos(List.copyOf(lineage.purchasePlanNos))
                .setMaterialAvailabilityStatus(readiness)
                .setMaterialAvailableTime(incomplete ? null : source.getMaterialAvailableTime())
                .setMaterialCount(source.getMaterialCount())
                .setShortageCount(incomplete ? null : source.getShortageCount())
                .setReservationRiskCount(source.getReservationRiskCount())
                .setActionRequiredCount(source.getActionRequiredCount())
                .setSupplyEvidenceGapCount(source.getSupplyEvidenceGapCount())
                .setShortageFactFingerprint(source.getFactFingerprint())
                .setMaterialBalanceCount(relevant.size())
                .setMaterialBalances(relevant.stream().limit(100)
                        .map(line -> materialBalance(line, lineage.materialCodes.contains(line.getMaterialCode())))
                        .toList())
                .setShortageLines(source.getLines().stream()
                        .filter(line -> line.getShortageNum() != null && line.getShortageNum().signum() > 0)
                        .limit(100).toList());
    }

    /** 同物料可以属于不同库仓或 MRP 行。逐行使用 PLAN 原量，不从采购实收量推算分配。 */
    private PlanIncomingAvailabilityImpactDTO.MaterialBalance materialBalance(PlanMrpShortageLineDTO line,
                                                                             boolean receiptMaterial) {
        return new PlanIncomingAvailabilityImpactDTO.MaterialBalance(line.getMrpId().toString(), receiptMaterial,
                line.getMaterialCode(), line.getMaterialName(), line.getMaterialUnit(), line.getDemandWarehouseCode(),
                quantityText(line.getGrossDemandNum()), quantityText(line.getAllocatedNum()),
                quantityText(line.getRecordedRemainingDemandNum()));
    }

    private String quantityText(BigDecimal quantity) {
        return quantity == null ? null : quantity.toPlainString();
    }

    private String fingerprint(PlanIncomingAvailabilityImpactDTO.Evidence evidence) {
        try {
            String facts = objectMapper.writeValueAsString(List.of(evidence.getFactoryCode(),
                    evidence.getDemands(), evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("PLAN来料齐套影响无法生成完整事实指纹");
        }
    }

    private java.util.Optional<DemandLineage> resolveDemand(
            PlanIncomingAvailabilityImpactDTO.SourceReference source, Long tenantId, String factoryCode,
            List<String> gaps, List<String> refs) {
        Long orderDetailId = positiveId(source.getPurchaseOrderDetailId());
        List<PlanMatMrpEntity> direct = mrpService.list(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getTenantId, tenantId)
                .eq(PlanMatMrpEntity::getFactoryCode, factoryCode)
                .eq(PlanMatMrpEntity::getPurchaseOrderDetailId, orderDetailId)
                .eq(PlanMatMrpEntity::getDelFlag, "0").last("LIMIT 2"));
        if (direct.size() == 1) {
            PlanMatMrpEntity mrp = direct.get(0);
            if (!Objects.equals(source.getMaterialCode(), mrp.getMatCode()) || !safeDemandNo(mrp.getPlanDemandNo())) {
                gaps.add("采购订单行" + orderDetailId + "在PLAN的MRP回执物料或需求来源不一致");
                return java.util.Optional.empty();
            }
            refs.add("PLAN:plan_mat_mrp:" + mrp.getId());
            return java.util.Optional.of(new DemandLineage(mrp.getPlanDemandNo()));
        }
        if (direct.size() > 1) {
            gaps.add("采购订单行" + orderDetailId + "关联多条PLAN采购回执，禁止猜测需求归属");
            return java.util.Optional.empty();
        }
        Long issueMonthId = positiveId(source.getSourceIssueMonthId());
        if (issueMonthId == null) {
            gaps.add("采购订单行" + orderDetailId + "既无PLAN采购回执，也缺少月计划来源主键");
            return java.util.Optional.empty();
        }
        List<PlnIssueMonthEntity> issues = issueMonthMapper.selectList(
                new LambdaQueryWrapper<PlnIssueMonthEntity>()
                        .eq(PlnIssueMonthEntity::getTenantId, tenantId)
                        .eq(PlnIssueMonthEntity::getFactoryCode, factoryCode)
                        .eq(PlnIssueMonthEntity::getId, issueMonthId)
                        .eq(PlnIssueMonthEntity::getDelFlag, "0").last("LIMIT 2"));
        if (issues.size() != 1) {
            gaps.add("采购订单行" + orderDetailId + "的PLAN月计划来源不存在或不唯一");
            return java.util.Optional.empty();
        }
        PlnIssueMonthEntity issue = issues.get(0);
        if (!Objects.equals(issue.getDownstreamPlanNo(), source.getPurchasePlanNo())
                || !Objects.equals(issue.getProductCode(), source.getMaterialCode())
                || !safeDemandNo(issue.getSourceDemandNo())) {
            gaps.add("采购订单行" + orderDetailId + "的PLAN月计划未保留一致的采购计划、物料和产品需求来源");
            return java.util.Optional.empty();
        }
        refs.add("PLAN:pln_issue_month:" + issue.getId());
        return java.util.Optional.of(new DemandLineage(issue.getSourceDemandNo()));
    }

    private boolean validSource(PlanIncomingAvailabilityImpactDTO.SourceReference source) {
        return source != null && positiveId(source.getPurchaseOrderDetailId()) != null
                && safeText(source.getPurchasePlanNo()) && safeText(source.getMaterialCode())
                && (source.getSourceIssueMonthId() == null || source.getSourceIssueMonthId().isBlank()
                || positiveId(source.getSourceIssueMonthId()) != null);
    }

    private String sourceKey(PlanIncomingAvailabilityImpactDTO.SourceReference source) {
        return String.join("\u0001", source.getPurchaseOrderDetailId(), source.getPurchasePlanNo(),
                Objects.toString(source.getSourceIssueMonthId(), ""), source.getMaterialCode());
    }

    private Long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private boolean safeText(String value) {
        return StringUtils.hasText(value) && value.length() <= 100
                && value.codePoints().noneMatch(Character::isISOControl)
                && Objects.equals(value, value.trim());
    }

    private boolean safeDemandNo(String value) {
        return safeText(value);
    }

    private record DemandLineage(String demandNo) {
    }

    private static final class ResolvedDemand {
        private final String demandNo;
        private final LinkedHashSet<String> orderDetailIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> purchasePlanNos = new LinkedHashSet<>();
        /** 已按采购行回执或月计划来源核验归属本需求的收料物料。 */
        private final Set<String> materialCodes = new LinkedHashSet<>();

        private ResolvedDemand(String demandNo) {
            this.demandNo = demandNo;
        }

        private String demandNo() {
            return demandNo;
        }

        private ResolvedDemand add(PlanIncomingAvailabilityImpactDTO.SourceReference source) {
            orderDetailIds.add(source.getPurchaseOrderDetailId());
            purchasePlanNos.add(source.getPurchasePlanNo());
            materialCodes.add(source.getMaterialCode());
            return this;
        }
    }
}
