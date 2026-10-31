package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 从一次实际组织配置读取构建完整方案；策略只读一次，后续求值不回查配置。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionPlanFactory {
    private final QualityStandardService standards;
    private final QualityStandardItemService items;
    private final QualitySamplingStrategiesService sampling;
    private final QmsInspectionHistorySupport access;

    @Transactional(
            readOnly = true,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ)
    public QmsFrozenInspectionPlan prepare(
            String type, QmsQualityStandardEntity standard, int batch, Integer requested) {
        access.requireScope();
        require("PRODUCT".equals(type) || "PROCEDURE".equals(type), "不支持的检验类型");
        require(
                standard != null
                        && standard.getId() != null
                        && standard.getId() > 0
                        && Objects.equals(standard.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                standard.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(standard.getInspectionType(), type)
                        && Objects.equals(standard.getPublished(), 1),
                "冻结方案缺少实际组织的已发布标准");
        Long expectedId = standard.getId();
        String expectedMaterial = standard.getMatCode();
        // 在同一只读RR快照内重读标准、项目和各策略，防止拼接两个已提交配置版本。
        standard =
                standards.getOne(
                        new LambdaQueryWrapper<QmsQualityStandardEntity>()
                                .eq(QmsQualityStandardEntity::getId, standard.getId())
                                .eq(
                                        QmsQualityStandardEntity::getTenantId,
                                        TenantContextHolder.getTenantId())
                                .eq(
                                        QmsQualityStandardEntity::getFactoryCode,
                                        FactoryContextHolder.getFactoryCode()));
        require(
                standard != null
                        && Objects.equals(standard.getId(), expectedId)
                        && Objects.equals(standard.getMatCode(), expectedMaterial)
                        && Objects.equals(standard.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                standard.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(standard.getInspectionType(), type)
                        && Objects.equals(standard.getPublished(), 1),
                "配置快照中的标准不再可用，请重新选择已发布标准");
        var rows =
                items.list(
                        new LambdaQueryWrapper<QmsQualityStandardItemEntity>()
                                .eq(QmsQualityStandardItemEntity::getStandardId, standard.getId())
                                .eq(
                                        QmsQualityStandardItemEntity::getTenantId,
                                        standard.getTenantId())
                                .eq(
                                        QmsQualityStandardItemEntity::getFactoryCode,
                                        standard.getFactoryCode())
                                .last("LIMIT 5001"));
        require(rows != null && !rows.isEmpty() && rows.size() <= 5000, "冻结方案缺少完整有界的标准项目");
        var ids = new HashSet<Long>();
        var policies = new ArrayList<QmsFrozenInspectionPlan.Item>();
        var strategies = new HashMap<Long, List<Frequency>>();
        for (var row : rows) {
            require(
                    row != null
                            && row.getId() != null
                            && row.getId() > 0
                            && ids.add(row.getId())
                            && Objects.equals(row.getStandardId(), standard.getId())
                            && Objects.equals(row.getTenantId(), standard.getTenantId())
                            && Objects.equals(row.getFactoryCode(), standard.getFactoryCode()),
                    "冻结标准项目缺失、重复或跨组织");
            Long strategyId = null;
            List<Frequency> frequencies = null;
            if ("RANDOM".equals(standard.getInspectionMethod())) {
                require(
                        row.getSamplingStrategiesId() != null
                                && row.getSamplingStrategiesId().matches("[1-9][0-9]{0,18}"),
                        "冻结项目缺少有效抽样策略");
                try {
                    strategyId = Long.valueOf(row.getSamplingStrategiesId());
                } catch (NumberFormatException e) {
                    throw new CheckedException("抽样策略编号超出范围", e);
                }
                frequencies = strategies.get(strategyId);
                if (frequencies == null) {
                    var strategy =
                            sampling.getOne(
                                    new LambdaQueryWrapper<QmsQualitySamplingStrategiesEntity>()
                                            .eq(
                                                    QmsQualitySamplingStrategiesEntity::getId,
                                                    strategyId)
                                            .eq(
                                                    QmsQualitySamplingStrategiesEntity::getTenantId,
                                                    standard.getTenantId())
                                            .eq(
                                                    QmsQualitySamplingStrategiesEntity
                                                            ::getFactoryCode,
                                                    standard.getFactoryCode()));
                    require(
                            strategy != null
                                    && Objects.equals(strategy.getId(), strategyId)
                                    && Objects.equals(
                                            strategy.getTenantId(), standard.getTenantId())
                                    && Objects.equals(
                                            strategy.getFactoryCode(), standard.getFactoryCode()),
                            "实际组织没有指定抽样策略");
                    frequencies = strategy.getFrequency();
                    require(
                            frequencies != null
                                    && !frequencies.isEmpty()
                                    && frequencies.size() <= 1000,
                            "完整抽样区间缺失或超过1000条");
                    // 检查完整政策的结构和未来区间；不只截取当前命中的一条。
                    QmsInspectionSamplingRules.frequency(batch, frequencies);
                    strategies.put(strategyId, frequencies);
                }
            }
            policies.add(QmsFrozenInspectionPlan.Item.of(row, strategyId, frequencies));
        }
        policies.sort(Comparator.comparing(QmsFrozenInspectionPlan.Item::standardItemId));
        var plan =
                new QmsFrozenInspectionPlan(
                        standard.getId(),
                        standard.getStandardName(),
                        standard.getInspectionMethod(),
                        standard.getNum(),
                        requested,
                        List.copyOf(policies));
        plan.targets(batch);
        return plan;
    }
}
