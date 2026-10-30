package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.PlanQualitySupplementProgressDTO;
import com.kitex.mom.plan.entity.PlanMatMrpEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMpsLineEntity;
import com.kitex.mom.plan.entity.PlnQualitySupplementDemandEntity;
import com.kitex.mom.plan.mapper.PlnQualitySupplementDemandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 补产全部根计划的只读归属和进度；读取不推进状态，列表统一批量取数。
 */
@Component
@RequiredArgsConstructor
public class PlnQualitySupplementProgressReader {
    private final PlnQualitySupplementDemandMapper supplementMapper;

    private static boolean zeroExecution(PlnIssueEntity issue) {
        return Arrays.asList(issue.getFinishNum(), issue.getUnqualifiedNum(), issue.getScrapNum(), issue.getReworkNum(),
                        issue.getQualityIsolatedNum(), issue.getQualityReleasedNum(), issue.getQualityRejectedNum(),
                        issue.getReworkCompletedNum(), issue.getReworkQualifiedNum(), issue.getReworkRejectedNum())
                .stream().allMatch(value -> nz(value).signum() == 0);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 完整归属集合用于进度、完工与撤回；保留代表ID只兼容历史页面字段。
     */
    public List<PlnIssueEntity> ownedRoots(PlnQualitySupplementDemandEntity supplement, List<PlanMatMrpEntity> rows) {
        require(TenantContextHolder.getTenantId() != null && TenantContextHolder.getTenantId() > 0
                        && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && StrUtil.isNotBlank(supplement.getFactoryCode())
                        && Objects.equals(FactoryContextHolder.getFactoryCode(), supplement.getFactoryCode()),
                "补产根计划必须在实际租户工厂读取");
        var roots = supplementMapper.demandRoots(TenantContextHolder.getTenantId(), supplement.getFactoryCode(), supplement.getProductDemandNo());
        var buckets = supplementMapper.liveMps(TenantContextHolder.getTenantId(), supplement.getFactoryCode(), supplement.getProductDemandId())
                .stream().collect(Collectors.toMap(PlnMpsLineEntity::getId, java.util.function.Function.identity()));
        return checkedRoots(supplement, rows, roots, buckets);
    }

    private List<PlnIssueEntity> checkedRoots(PlnQualitySupplementDemandEntity supplement, List<PlanMatMrpEntity> rows,
                                              List<PlnIssueEntity> roots, Map<Long, PlnMpsLineEntity> buckets) {
        require(rows != null && !rows.isEmpty() && rows.stream().allMatch(row -> row != null
                        && Objects.equals(row.getFactoryCode(), supplement.getFactoryCode())
                        && Objects.equals(row.getPlanDemandNo(), supplement.getProductDemandNo())),
                "补产有效MRP缺失或来源范围不一致");
        var ids = rows.stream().map(PlanMatMrpEntity::getMpsLineId).filter(Objects::nonNull).collect(Collectors.toSet());
        require(rows.stream().allMatch(row -> ids.isEmpty() ? row.getMpsLineId() == null : row.getMpsLineId() != null),
                "补产MRP混用MPS与普通来源");
        if (ids.isEmpty()) require(rows.stream().allMatch(row -> row.getMpsVersionId() == null
                        && Objects.equals(row.getPlanningSourceKey(), "DEMAND-" + supplement.getProductDemandId())),
                "普通补产MRP来源键或MPS关系不一致");
        var current = new ArrayList<PlnIssueEntity>();
        for (var issue : roots) {
            boolean selected = ids.isEmpty() ? issue.getMpsLineId() == null : ids.contains(issue.getMpsLineId());
            if (!selected) {
                require("4".equals(issue.getStatus()) && zeroExecution(issue), "补产存在未安全关闭的旧桶根计划：" + issue.getIssueNo() + "/" + issue.getStatus());
                continue;
            }
            require(!"1".equals(issue.getDelFlag()) && Objects.equals(issue.getProductCode(), supplement.getProductCode())
                            && issue.getNum() != null && issue.getNum().signum() > 0
                            && nz(issue.getFinishNum()).signum() >= 0 && nz(issue.getFinishNum()).compareTo(issue.getNum()) <= 0,
                    "补产根计划来源、状态或完成量不合法");
            if (ids.isEmpty()) {
                require("quality_supplement".equals(issue.getType()) && "QUALITY_SUPPLEMENT".equals(issue.getPlanType())
                                && Objects.equals(issue.getIssueNo(), supplement.getProductDemandNo())
                                && Objects.equals(issue.getPlanningSourceKey(), "DEMAND-" + supplement.getProductDemandId()),
                        "补产普通根计划来源不一致");
            } else {
                var bucket = buckets.get(issue.getMpsLineId());
                require(bucket != null && Boolean.TRUE.equals(bucket.getActive()) && !Boolean.TRUE.equals(bucket.getPending())
                                && Objects.equals(bucket.getDemandId(), supplement.getProductDemandId())
                                && Objects.equals(bucket.getDemandNo(), supplement.getProductDemandNo())
                                && Objects.equals(bucket.getFactoryCode(), supplement.getFactoryCode())
                                && Objects.equals(bucket.getBucketKey(), issue.getPlanningSourceKey())
                                && bucket.getPlanQuantity() != null && bucket.getPlanQuantity().compareTo(issue.getNum()) == 0
                                && Objects.equals(issue.getIssueNo(), "MPS-" + bucket.getId()) && "issue".equals(issue.getType())
                                && rows.stream().filter(row -> Objects.equals(row.getMpsLineId(), bucket.getId()))
                                .allMatch(row -> Objects.equals(row.getMpsVersionId(), bucket.getVersionId())
                                        && Objects.equals(row.getPlanningSourceKey(), bucket.getBucketKey())),
                        "补产根计划未精确对应当前MPS桶");
            }
            current.add(issue);
        }
        require(current.size() == (ids.isEmpty() ? 1 : ids.size())
                && current.stream().map(PlnIssueEntity::getMpsLineId).filter(Objects::nonNull).distinct().count() == ids.size()
                && current.stream().map(PlnIssueEntity::getNum).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(supplement.getRequestQuantity()) == 0, "补产根计划缺桶、重复或总量与批准数量不一致");
        return current;
    }

    public List<PlanMatMrpEntity> mrpRows(PlnQualitySupplementDemandEntity supplement) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                        && Objects.equals(FactoryContextHolder.getFactoryCode(), supplement.getFactoryCode()),
                "补产MRP必须在实际租户工厂读取");
        return supplementMapper.demandMrp(TenantContextHolder.getTenantId(), supplement.getFactoryCode(), supplement.getProductDemandNo());
    }

    public BigDecimal completed(List<PlnIssueEntity> roots) {
        return roots.stream().map(PlnIssueEntity::getFinishNum).map(PlnQualitySupplementProgressReader::nz)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 列表只执行三次批量本域读取，不逐补产/逐桶请求数据库。
     */
    public void enrichAll(List<PlnQualitySupplementDemandEntity> supplements) {
        var planned = supplements.stream().filter(row -> Integer.valueOf(2).equals(row.getPlanningModelVersion())
                && Set.of("MRP_GENERATED", "FULFILLED").contains(row.getStatus())).toList();
        for (var row : supplements) if ("DEMAND_CREATED".equals(row.getStatus())) enrich(row, List.of());
        if (planned.isEmpty()) return;
        var tenant = TenantContextHolder.getTenantId();
        var factory = FactoryContextHolder.getFactoryCode();
        require(tenant != null && tenant > 0 && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory)
                        && planned.stream().allMatch(row -> Objects.equals(factory, row.getFactoryCode())
                        && StrUtil.isNotBlank(row.getProductDemandNo()) && row.getProductDemandId() != null),
                "补产进度须在同一实际租户工厂读取");
        var nos = planned.stream().map(PlnQualitySupplementDemandEntity::getProductDemandNo).distinct().toList();
        var ids = planned.stream().map(PlnQualitySupplementDemandEntity::getProductDemandId).distinct().toList();
        var roots = supplementMapper.rootsForDemands(tenant, factory, nos).stream()
                .collect(Collectors.groupingBy(PlnIssueEntity::getSourceDemandNo));
        var mrp = supplementMapper.mrpForDemands(tenant, factory, nos).stream()
                .collect(Collectors.groupingBy(PlanMatMrpEntity::getPlanDemandNo));
        var buckets = supplementMapper.mpsForDemands(tenant, factory, ids).stream()
                .collect(Collectors.toMap(PlnMpsLineEntity::getId, java.util.function.Function.identity()));
        for (var row : planned)
            enrichPlanned(row, mrp.getOrDefault(row.getProductDemandNo(), List.of()),
                    roots.getOrDefault(row.getProductDemandNo(), List.of()), buckets);
    }

    public void enrich(PlnQualitySupplementDemandEntity supplement, List<PlanMatMrpEntity> rows) {
        if (!Integer.valueOf(2).equals(supplement.getPlanningModelVersion())) return;
        if ("DEMAND_CREATED".equals(supplement.getStatus())) {
            supplement.setDownstreamStatus("PLANNING_PENDING").setDownstreamMessage("需求已接收，等待MPS / MRP计划")
                    .setCompletedQuantity(BigDecimal.ZERO).setProductionPlans(List.of());
            return;
        }
        if (!Set.of("MRP_GENERATED", "FULFILLED").contains(supplement.getStatus())) return;
        enrichPlanned(supplement, rows, supplementMapper.demandRoots(TenantContextHolder.getTenantId(),
                        supplement.getFactoryCode(), supplement.getProductDemandNo()),
                supplementMapper.liveMps(TenantContextHolder.getTenantId(), supplement.getFactoryCode(), supplement.getProductDemandId())
                        .stream().collect(Collectors.toMap(PlnMpsLineEntity::getId, java.util.function.Function.identity())));
    }

    private void enrichPlanned(PlnQualitySupplementDemandEntity supplement, List<PlanMatMrpEntity> rows,
                               List<PlnIssueEntity> rootRows, Map<Long, PlnMpsLineEntity> buckets) {
        try {
            var roots = checkedRoots(supplement, rows, rootRows, buckets);
            supplement.setMrpLineCount(rows.size()).setMrpIssuedLineCount((int) rows.stream().filter(row -> Boolean.TRUE.equals(row.getStatus())).count());
            supplement.setCompletedQuantity(completed(roots));
            supplement.setProductionPlans(roots.stream().map(root -> new PlanQualitySupplementProgressDTO.RootPlan()
                    .setId(root.getId()).setIssueNo(root.getIssueNo()).setDemandDate(root.getDemandTime())
                    .setBucketNo(root.getMpsLineId() == null ? null : buckets.get(root.getMpsLineId()).getBucketNo())
                    .setQuantity(root.getNum()).setCompletedQuantity(nz(root.getFinishNum()))
                    .setStatus(root.getStatus()).setLocked(root.getLockStatus())).toList());
            if ("FULFILLED".equals(supplement.getStatus())) {
                require(completed(roots).compareTo(supplement.getRequestQuantity()) == 0, "补产已完成状态与实际完成总量不一致");
                supplement.setDownstreamStatus("FULFILLED").setDownstreamMessage("补产数量已完成并回写PLAN");
                return;
            }
            if (rows.stream().anyMatch(row -> !Boolean.TRUE.equals(row.getStatus()))) {
                supplement.setDownstreamStatus("MRP_PENDING").setDownstreamMessage("MRP已生成，等待物料供应计划下达");
                return;
            }
            if (roots.stream().anyMatch(root -> "2".equals(root.getStatus())))
                supplement.setDownstreamStatus("MES_EXECUTING");
            else if (roots.stream().anyMatch(root -> "1".equals(root.getStatus())))
                supplement.setDownstreamStatus("MES_ACCEPTED");
            else if (roots.stream().anyMatch(root -> !Boolean.TRUE.equals(root.getLockStatus())))
                supplement.setDownstreamStatus("APS_PENDING");
            else if (roots.stream().allMatch(root -> "3".equals(root.getStatus())))
                supplement.setDownstreamStatus("COMPLETION_DEFICIT");
            else if (roots.stream().anyMatch(root -> "4".equals(root.getStatus())))
                supplement.setDownstreamStatus("CLOSED_UNFULFILLED");
            else supplement.setDownstreamStatus("MES_PENDING");
            supplement.setDownstreamMessage(switch (supplement.getDownstreamStatus()) {
                case "MES_EXECUTING" -> "补产计划正在生产";
                case "MES_ACCEPTED" -> "MES已接收补产计划";
                case "APS_PENDING" -> "等待补产计划排产锁定";
                case "COMPLETION_DEFICIT" -> "计划已完工，合计完成量仍不足";
                case "CLOSED_UNFULFILLED" -> "存在未完成已关闭计划，须核对缺口";
                default -> "补产计划等待下达MES";
            });
        } catch (CheckedException ex) {
            supplement.setDownstreamStatus("SOURCE_CONFLICT").setDownstreamMessage(ex.getMessage())
                    .setCompletedQuantity(BigDecimal.ZERO).setProductionPlans(List.of());
        }
    }
}
