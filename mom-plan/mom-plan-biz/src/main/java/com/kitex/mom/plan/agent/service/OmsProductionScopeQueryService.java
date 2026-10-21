package com.kitex.mom.plan.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.agent.dto.MesPlanTaskScopeDTO;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.oms.agent.dto.OmsProductionScopeDTO;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.plan.agent.mapper.OmsProductionScopeOwnershipMapper;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.OmsOrderDetailsMapper;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnProductDemandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 订单级生产调查范围解析。只使用OMS交付行、PLAN来源需求/计划、MES直接/合批分配账。
 * 不复用全生命周期中的采购和发运查询，任一领域失败明确留缺口，不猜测同产品订单归属。
 */
@Service
@RequiredArgsConstructor
public class OmsProductionScopeQueryService {

    private final OmsOrderDetailsMapper detailsMapper;
    private final PlnProductDemandMapper demandMapper;
    private final PlnIssueMapper issueMapper;
    private final RemoteMesTaskService remoteMes;
    private final OmsProductionScopeOwnershipMapper ownershipMapper;

    /** 解析单个实名租户/工厂的精确销售订单，所有查询有固定安全边界。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OmsProductionScopeDTO.Evidence query(OmsProductionScopeDTO.Query query) {
        String factory = FactoryContextHolder.getFactoryCode();
        require(SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null
                        && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory),
                "订单生产范围必须实名登录并选择实际工厂，不能跳过租户或工厂隔离");
        require(query != null && Objects.equals(factory, query.getFactoryCode())
                        && StrUtil.isNotBlank(query.getOrderCode()) && query.getOrderCode().trim().length() <= 100,
                "订单生产范围必须提供当前工厂及有效销售订单编码");
        String order = query.getOrderCode().trim();
        require(ownershipMapper.countSafeOwner(TenantContextHolder.getTenantId(), factory, order) == 1,
                "订单未唯一归属当前租户/工厂，不能采用旧版无租户字段的交付行");
        List<OmsOrderDetailsEntity> details = detailsMapper.selectList(
                new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                        .eq(OmsOrderDetailsEntity::getFactoryCode, factory)
                        .eq(OmsOrderDetailsEntity::getOrderCode, order)
                        .orderByAsc(OmsOrderDetailsEntity::getId).last("LIMIT 101"));
        require(!details.isEmpty(), "当前工厂未找到销售订单交付行");
        require(details.size() <= 100, "订单交付行超过100条，请按受控业务范围拆分调查");
        List<PlnProductDemandEntity> demands = demandMapper.selectList(
                new LambdaQueryWrapper<PlnProductDemandEntity>()
                        .eq(PlnProductDemandEntity::getFactoryCode, factory)
                        .eq(PlnProductDemandEntity::getSaleOrderCode, order)
                        .orderByAsc(PlnProductDemandEntity::getId).last("LIMIT 1001"));
        require(demands.size() <= 1000, "订单来源需求超过1000条安全边界，须治理来源关联");
        List<OmsProductionScopeDTO.Line> lines = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        Map<String, OmsProductionScopeDTO.Line> planSources = new LinkedHashMap<>();
        Map<String, String> planProducts = new LinkedHashMap<>();
        for (OmsOrderDetailsEntity detail : details) {
            refs.add("OMS:oms_order_details:" + detail.getId());
            OmsProductionScopeDTO.Line line = new OmsProductionScopeDTO.Line()
                    .setLineId(detail.getId()).setProductCode(detail.getProductCode())
                    .setProductUnit(detail.getProductUnit()).setOrderedQuantity(detail.getDeliverNum())
                    .setQuantityAttributionStatus("DATA_GAP").setQuantityDataGaps(new ArrayList<>())
                    .setPlanNos(new ArrayList<>());
            lines.add(line);
            List<PlnProductDemandEntity> linked = demands.stream()
                    .filter(demand -> Objects.equals(detail.getId(), demand.getSaleOrderCodeDetails())).toList();
            if (linked.isEmpty()) {
                line.setSourceStatus("DATA_GAP");
                gaps.add("交付行" + detail.getId() + "尚无PLAN来源需求，不能判定订单全部工单范围");
                continue;
            }
            if (linked.size() != 1 || !consistent(detail, linked.get(0))) {
                line.setSourceStatus("DATA_GAP");
                gaps.add("交付行" + detail.getId() + "来源需求重复或产品/数量/交期不一致，未采用该映射");
                continue;
            }
            String demandNo = linked.get(0).getDemandNo();
            long uniqueDemand = demandMapper.selectCount(new LambdaQueryWrapper<PlnProductDemandEntity>()
                    .eq(PlnProductDemandEntity::getFactoryCode, factory)
                    .eq(PlnProductDemandEntity::getDemandNo, demandNo));
            if (uniqueDemand != 1) {
                line.setSourceStatus("DATA_GAP");
                gaps.add("交付行" + detail.getId() + "的来源需求编码不唯一，不能跨订单猜测计划归属");
                continue;
            }
            line.setDemandNo(demandNo);
            refs.add("PLAN:pln_product_demand:" + demandNo);
            List<PlnIssueEntity> issues = issueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                    .eq(PlnIssueEntity::getFactoryCode, factory)
                    .and(where -> where.eq(PlnIssueEntity::getSourceDemandNo, demandNo)
                            .or().and(legacy -> legacy.isNull(PlnIssueEntity::getSourceDemandNo)
                                    .eq(PlnIssueEntity::getIssueNo, demandNo)))
                    .orderByAsc(PlnIssueEntity::getId).last("LIMIT 101"));
            require(issues.size() <= 100, "单行生产计划超过100条安全边界，请拆分调查");
            line.setSourceStatus(issues.isEmpty() ? "NOT_STARTED" : "AVAILABLE");
            for (PlnIssueEntity issue : issues) {
                require(StrUtil.isNotBlank(issue.getIssueNo()), "来源生产计划缺少编号，须先治理");
                require(issueMapper.selectCount(new LambdaQueryWrapper<PlnIssueEntity>()
                                .eq(PlnIssueEntity::getFactoryCode, factory)
                                .eq(PlnIssueEntity::getIssueNo, issue.getIssueNo())) == 1,
                        "来源生产计划编号不唯一，不能跨订单猜测工单归属");
                require(!planSources.containsKey(issue.getIssueNo()), "订单来源生产计划编号重复，须先治理");
                planSources.put(issue.getIssueNo(), line);
                planProducts.put(issue.getIssueNo(), issue.getProductCode());
                line.getPlanNos().add(issue.getIssueNo());
                refs.add("PLAN:pln_issue:" + issue.getIssueNo());
                if (issue.getSourceDemandNo() == null) {
                    gaps.add("计划" + issue.getIssueNo() + "使用旧版同号来源关联，须补齐明确需求来源");
                }
            }
        }
        if (demands.stream().anyMatch(demand -> details.stream().noneMatch(detail ->
                Objects.equals(detail.getId(), demand.getSaleOrderCodeDetails())))) {
            gaps.add("当前订单存在无法对应交付行的PLAN来源需求，不能宣称整单来源完整");
        }
        require(planSources.size() <= 100, "整单生产计划超过100条，须拆分受控调查范围");
        MesPlanTaskScopeDTO.Evidence mes = null;
        if (!planSources.isEmpty()) {
            try {
                R<MesPlanTaskScopeDTO.Evidence> response = remoteMes.planTaskScope(
                        new MesPlanTaskScopeDTO.Query().setFactoryCode(factory)
                                .setPlanNos(new ArrayList<>(planSources.keySet())));
                require(response != null && response.isOk() && response.getData() != null,
                        "MES未返回有效来源工单范围");
                mes = response.getData();
                require(factory.equals(mes.getFactoryCode()) && mes.getMatchedTaskCount() != null
                                && mes.getTasks() != null && mes.getLinks() != null && mes.getDataGaps() != null,
                        "MES来源工单范围不符合当前工厂契约");
            } catch (RuntimeException exception) {
                mes = null;
                gaps.add("MES来源范围不可用，不能把工单数量解释为0或整单尚未开工");
            }
        }
        List<OmsProductionScopeDTO.Link> links = new ArrayList<>();
        if (mes != null) {
            gaps.addAll(mes.getDataGaps());
            for (MesPlanTaskScopeDTO.Link source : mes.getLinks()) {
                OmsProductionScopeDTO.Line line = planSources.get(source.getPlanNo());
                require(line != null, "MES返回了订单范围外的计划来源，拒绝采用");
                links.add(new OmsProductionScopeDTO.Link().setLineId(line.getLineId())
                        .setDemandNo(line.getDemandNo()).setPlanNo(source.getPlanNo())
                        .setMaterialCode(source.getMaterialCode()).setAllocationId(source.getAllocationId())
                        .setAllocatedQuantity(source.getAllocatedQuantity()).setCompletedQuantity(source.getCompletedQuantity())
                        .setInStockQuantity(source.getInStockQuantity()).setQuantityAttributionStatus(source.getQuantityAttributionStatus())
                        .setQuantityDataGaps(source.getQuantityDataGaps())
                        .setTaskCode(source.getTaskCode()).setRelationType(source.getRelationType()));
                refs.add("MES:mes_task:" + source.getTaskCode());
            }
            for (String plan : planSources.keySet()) {
                if (mes.getLinks().stream().noneMatch(link -> plan.equals(link.getPlanNo()))) {
                    gaps.add("生产计划" + plan + "未取得MES工单映射；尚未下发或关联缺失需人工核验");
                }
            }
        }
        boolean truncated = mes != null && Boolean.TRUE.equals(mes.getTruncated());
        // 仅归属本交付行成品计划；BOM自制件仍可调查，但绝不叠加到订单完工量。
        for (OmsProductionScopeDTO.Line line : lines) {
            List<String> quantityGaps = line.getQuantityDataGaps();
            List<String> roots = line.getPlanNos().stream()
                    .filter(plan -> Objects.equals(line.getProductCode(), planProducts.get(plan))).toList();
            if (!"AVAILABLE".equals(line.getSourceStatus()) || roots.isEmpty()) {
                quantityGaps.add("尚未取得唯一交付行对应的成品生产计划，不将数量解释为零");
                continue;
            }
            if (StrUtil.isBlank(line.getProductUnit()) || mes == null || truncated) {
                quantityGaps.add("交付行单位、MES数量账或完整范围不足");
                continue;
            }
            List<OmsProductionScopeDTO.Link> production = links.stream()
                    .filter(link -> Objects.equals(line.getLineId(), link.getLineId()) && roots.contains(link.getPlanNo())).toList();
            for (String root : roots) {
                if (production.stream().noneMatch(link -> root.equals(link.getPlanNo()))) quantityGaps.add("成品计划" + root + "缺少工单数量来源");
            }
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (OmsProductionScopeDTO.Link source : production) {
                if (!Objects.equals(line.getProductCode(), source.getMaterialCode())
                        || !("ALLOCATED".equals(source.getQuantityAttributionStatus())
                            || "DIRECT_EXCLUSIVE".equals(source.getQuantityAttributionStatus()))
                        || source.getCompletedQuantity() == null || source.getInStockQuantity() == null
                        || !keys.add(source.getAllocationId() == null ? "DIRECT:" + source.getTaskCode()
                                : "ALLOCATION:" + source.getAllocationId())) {
                    quantityGaps.add("来源" + source.getPlanNo() + "/" + source.getTaskCode() + "物料/数量或唯一归属不足");
                }
            }
            if (quantityGaps.isEmpty()) {
                line.setQuantityAttributionStatus("COMPLETE")
                        .setAttributedCompletedQuantity(production.stream().map(OmsProductionScopeDTO.Link::getCompletedQuantity)
                                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                        .setAttributedInStockQuantity(production.stream().map(OmsProductionScopeDTO.Link::getInStockQuantity)
                                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
            }
        }
        return new OmsProductionScopeDTO.Evidence().setFactoryCode(factory).setOrderCode(order)
                .setObservedAt(LocalDateTime.now()).setMatchedLineCount((long) details.size())
                .setLines(lines).setLinks(links).setTasks(mes == null ? List.of() : mes.getTasks().stream()
                        .map(task -> new OmsProductionScopeDTO.Task().setTaskId(task.getTaskId())
                                .setTaskCode(task.getTaskCode()).setMatCode(task.getMatCode())
                                .setStatus(task.getStatus()).setRiskObservedAt(task.getRiskObservedAt())
                                .setPlanEndTime(task.getPlanEndTime()).setActualFinishTime(task.getActualFinishTime())
                                .setDelayed(task.getDelayed()).setPausedOperationCount(task.getPausedOperationCount())
                                .setUnqualifiedQuantity(task.getUnqualifiedQuantity())
                                .setRiskDataGaps(task.getRiskDataGaps())).toList())
                .setMatchedTaskCount(mes == null ? (planSources.isEmpty() ? 0L : null) : mes.getMatchedTaskCount())
                .setScopeComplete(gaps.isEmpty() && !truncated).setTruncated(truncated)
                .setDataGaps(gaps).setEvidenceRefs(new ArrayList<>(refs))
                .setMetricDefinition("精确销售订单→交付行→唯一PLAN需求→成品/零件计划→MES来源；数量只按交付行成品编码及精确直接/合批分配账归属，合批须总账守恒，不按比例猜测，不跨单位合计。生产完工与入库不等于合格净可交量或客户交付；跨域顺序观察不是全局同一快照。");
    }

    private boolean consistent(OmsOrderDetailsEntity detail, PlnProductDemandEntity demand) {
        return StrUtil.isNotBlank(demand.getDemandNo())
                && StrUtil.isNotBlank(detail.getProductCode()) && detail.getDeliverTime() != null
                && Objects.equals(detail.getProductCode(), demand.getProductCode())
                && detail.getDeliverNum() != null && demand.getNeedNum() != null
                && detail.getDeliverNum().compareTo(demand.getNeedNum()) == 0
                && Objects.equals(detail.getDeliverTime(), demand.getPlanEndDate());
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
