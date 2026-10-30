package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import com.kitex.mom.plan.mapper.PlnQualitySupplementDemandMapper;
import com.kitex.mom.plan.mapper.PlnQualitySupplementWithdrawalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 已批准补产的计划投影。只操作PLAN本域，MRP/正式合同仍由正常入口负责。
 * 调用方持有工厂发布锁和需求锁，避免活动桶在投影/撤回期间换版。
 */
@Component
@RequiredArgsConstructor
public class PlnQualitySupplementPlanningProjector {
    private static final Set<String> COVERED = Set.of("DEMAND_CREATED", "MRP_GENERATED", "FULFILLED");
    private final PlnQualitySupplementDemandMapper supplementMapper;
    private final PlnQualitySupplementWithdrawalMapper withdrawalMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnQualitySupplementSourceGuard sourceGuard;
    private final PlnMpsRootPlanService rootPlans;
    private final PlnQualitySupplementProgressReader progress;

    static String sourceFingerprint(PlnIssueEntity issue) {
        try {
            String input = String.join("|", Objects.toString(issue.getId(), ""), Objects.toString(issue.getIssueNo(), ""),
                    Objects.toString(issue.getFactoryCode(), ""), Objects.toString(issue.getProductCode(), ""),
                    Objects.toString(issue.getNum(), ""), Objects.toString(issue.getScrapNum(), ""));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public boolean isStaged(PlnProductDemandEntity demand) {
        if (!"QUALITY_SUPPLEMENT".equals(demand.getSourceType()) || demand.getSourceId() == null) return false;
        var supplement = supplementMapper.selectById(demand.getSourceId());
        require(supplement != null, "质量补产需求来源不存在");
        return Integer.valueOf(2).equals(supplement.getPlanningModelVersion());
    }

    /**
     * 已锁需求之后、MRP副作用之前核验来源，不能把另一个需求接到补产额度。
     */
    public void validateForMrp(PlnProductDemandEntity demand) {
        if (!isStaged(demand)) return;
        PlnQualitySupplementSourceGuard.requireActor();
        var located = supplementMapper.selectById(demand.getSourceId());
        var source = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, located.getSourceIssueId()).last("FOR UPDATE"));
        require(source != null, "补产原生产计划不存在");
        var supplement = supplementMapper.selectOne(new LambdaQueryWrapper<PlnQualitySupplementDemandEntity>()
                .eq(PlnQualitySupplementDemandEntity::getId, demand.getSourceId()).last("FOR UPDATE"));
        validateBinding(supplement, demand);
        require(Set.of("DEMAND_CREATED", "MRP_GENERATED").contains(supplement.getStatus()), "补产当前状态不能继续计划");
        requireNoWithdrawal(supplement);
        require(Objects.equals(source.getFactoryCode(), supplement.getFactoryCode())
                        && Objects.equals(source.getIssueNo(), supplement.getSourceIssueNo())
                        && Objects.equals(source.getProductCode(), supplement.getProductCode())
                        && Objects.equals(sourceFingerprint(source), supplement.getQualityEvidenceFingerprint()),
                "补产原报废事实已变化，须先受控撤回并按最新事实申请");
        BigDecimal covered = supplementMapper.sourceSupplements(TenantContextHolder.getTenantId(),
                        source.getFactoryCode(), source.getId()).stream()
                .filter(row -> COVERED.contains(row.getStatus())).map(PlnQualitySupplementDemandEntity::getRequestQuantity)
                .map(PlnQualitySupplementPlanningProjector::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(covered.compareTo(nz(source.getScrapNum())) <= 0, "补产批准总量已超过当前报废量");
        require(Objects.equals(demand.getProductUnit(), sourceGuard.productUnit(source)), "补产需求计量与原报废计量不一致");
    }

    public void requireNoWithdrawal(PlnQualitySupplementDemandEntity supplement) {
        require(withdrawalMapper.selectCount(new LambdaQueryWrapper<PlnQualitySupplementWithdrawalEntity>()
                        .eq(PlnQualitySupplementWithdrawalEntity::getSupplementId, supplement.getId())
                        .in(PlnQualitySupplementWithdrawalEntity::getStatus,
                                List.of("SUBMITTED", "APPROVED", "EXECUTING", "PARTIAL", "FAILED"))) == 0,
                "补产已有待处理或失败待续跑的撤回单，禁止同时继续计划");
    }

    public void validateBinding(PlnQualitySupplementDemandEntity supplement, PlnProductDemandEntity demand) {
        PlnQualitySupplementSourceGuard.requireActor();
        require(supplement != null && Integer.valueOf(2).equals(supplement.getPlanningModelVersion())
                        && Objects.equals(FactoryContextHolder.getFactoryCode(), supplement.getFactoryCode())
                        && Objects.equals(supplement.getFactoryCode(), demand.getFactoryCode())
                        && Objects.equals(demand.getId(), supplement.getProductDemandId())
                        && Objects.equals(demand.getDemandNo(), supplement.getProductDemandNo())
                        && "QUALITY_SUPPLEMENT".equals(demand.getSourceType())
                        && Objects.equals(demand.getSourceId(), supplement.getId())
                        && Objects.equals(demand.getSourceNo(), supplement.getSupplementNo())
                        && Objects.equals(demand.getSourceEvidenceFingerprint(), supplement.getQualityEvidenceFingerprint())
                        && Objects.equals(demand.getProductCode(), supplement.getProductCode())
                        && Objects.equals(demand.getDemandWarehouseCode(), supplement.getDemandWarehouseCode())
                        && Objects.equals(demand.getPlanEndDate(), supplement.getDemandDate())
                        && demand.getNeedNum() != null && demand.getNeedNum().compareTo(supplement.getRequestQuantity()) == 0,
                "补产产品需求的来源、范围、数量、仓库或交期与批准记录不一致");
    }

    /**
     * 正常MRP正式合同发布成功之后调用，同事务创建完整根计划并回写状态。
     */
    public void onMrpReady(PlnProductDemandEntity demand, List<PlanMatMrpEntity> rows,
                           List<PlnMpsLineEntity> buckets) {
        if (!isStaged(demand)) return;
        var supplement = supplementMapper.selectById(demand.getSourceId());
        validateBinding(supplement, demand);
        require(rows != null && !rows.isEmpty(), "补产没有可下达MRP结果，请核对BOM和物料属性");
        require(rows.stream().allMatch(row -> Objects.equals(row.getPlanDemandNo(), demand.getDemandNo())
                && Objects.equals(row.getFactoryCode(), demand.getFactoryCode())), "补产MRP来源范围不一致");
        var sourceIssue = issueMapper.selectById(supplement.getSourceIssueId());
        require(sourceIssue != null && Objects.equals(sourceIssue.getFactoryCode(), demand.getFactoryCode())
                && Objects.equals(sourceIssue.getProductCode(), demand.getProductCode()), "补产原产品计划来源已变化");
        if (buckets.isEmpty()) {
            require(rows.stream().allMatch(row -> row.getMpsLineId() == null && row.getMpsVersionId() == null
                            && Objects.equals(row.getPlanningSourceKey(), "DEMAND-" + demand.getId())),
                    "普通补产MRP不得借用旧MPS桶");
            var roots = supplementMapper.demandRoots(TenantContextHolder.getTenantId(), demand.getFactoryCode(), demand.getDemandNo());
            require(roots.isEmpty(), "补产MRP前已有根生产计划，禁止重复或借用旧计划");
            var issue = new PlnIssueEntity();
            issue.setIssueNo(demand.getDemandNo());
            issue.setSourceDemandNo(demand.getDemandNo());
            issue.setPlanningSourceKey("DEMAND-" + demand.getId());
            issue.setFactoryCode(demand.getFactoryCode());
            issue.setCustomerCode(demand.getCustomerCode());
            issue.setCustomerName(demand.getCustomerName());
            issue.setProductCode(demand.getProductCode());
            issue.setProductName(demand.getProductName());
            issue.setSpecificationModel(demand.getSpecification());
            issue.setMaterialAttribute(sourceIssue.getMaterialAttribute());
            issue.setNum(demand.getNeedNum());
            issue.setFinishNum(BigDecimal.ZERO);
            issue.setIssueTime(LocalDateTime.now());
            issue.setDemandTime(demand.getPlanEndDate());
            issue.setPlanType("QUALITY_SUPPLEMENT");
            issue.setType("quality_supplement");
            issue.setStatus("0");
            issue.setLockStatus(false);
            require(issueMapper.insert(issue) == 1, "补产根生产计划保存失败");
        } else {
            var ids = buckets.stream().map(PlnMpsLineEntity::getId).collect(Collectors.toSet());
            require(ids.size() == buckets.size() && rows.stream().allMatch(row -> buckets.stream().anyMatch(bucket ->
                            Objects.equals(row.getMpsLineId(), bucket.getId())
                                    && Objects.equals(row.getMpsVersionId(), bucket.getVersionId())
                                    && Objects.equals(row.getPlanningSourceKey(), bucket.getBucketKey())))
                            && rows.stream().map(PlanMatMrpEntity::getMpsLineId).collect(Collectors.toSet()).equals(ids),
                    "补产MRP未完整对应本次MPS全部活动桶");
            require(supplementMapper.demandRoots(TenantContextHolder.getTenantId(), demand.getFactoryCode(), demand.getDemandNo())
                            .stream().noneMatch(root -> ids.contains(root.getMpsLineId())),
                    "补产当前MPS桶在MRP成功前已有根计划，须先核对来源");
            for (var bucket : buckets) {
                var root = rootPlans.create(bucket, demand);
                root.setMaterialAttribute(sourceIssue.getMaterialAttribute());
                require(issueMapper.updateById(root) == 1, "补产桶根计划产品属性保存失败");
            }
        }
        var roots = progress.ownedRoots(supplement, rows);
        require(!roots.isEmpty(), "补产根生产计划缺失");
        supplement.setStatus("MRP_GENERATED").setMrpGeneratedTime(LocalDateTime.now())
                .setDemandWarehouseName(demand.getDemandWarehouseName())
                .setSupplementIssueId(roots.get(0).getId()).setSupplementIssueNo(roots.get(0).getIssueNo());
        require(supplementMapper.updateById(supplement) == 1, "补产MRP及根计划回执保存失败");
    }
}
