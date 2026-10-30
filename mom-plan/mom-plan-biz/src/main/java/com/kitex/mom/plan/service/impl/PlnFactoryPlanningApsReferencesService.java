package com.kitex.mom.plan.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningApsReferencesDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningApsReferencesDTO.Reference;
import com.kitex.mom.plan.mapper.PlnFactoryPlanningApsReferenceMapper;
import com.kitex.mom.plan.mapper.PlnFactoryPlanningImpactMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

/**
 * Click-to-read evidence only; count and sample share a consistent read-only snapshot.
 */
@Service
@RequiredArgsConstructor
public class PlnFactoryPlanningApsReferencesService {
    private final PlnFactoryPlanningApsReferenceMapper references;
    private final PlnFactoryPlanningImpactMapper impact;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MdmFactoryPlanningApsReferencesDTO read(String factoryCode) {
        Long tenant = TenantContextHolder.getTenantId();
        if (tenant == null
                || tenant <= 0
                || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || factoryCode == null
                || factoryCode.isBlank()
                || !Objects.equals(factoryCode, FactoryContextHolder.getFactoryCode()))
            throw new CheckedException("排产占用明细必须使用实际租户及当前工厂");
        boolean cases = impact.apsCaseInstalled();
        if (cases != impact.apsDispatchInstalled())
            throw new CheckedException("智能APS结构不完整，请先完成模块升级");
        int limit = MdmFactoryPlanningApsReferencesDTO.MAX_RECORDS;
        long total = references.ordinaryCount(tenant, factoryCode);
        var rows = new ArrayList<>(references.ordinary(tenant, factoryCode, limit));
        if (cases) {
            total = Math.addExact(total, references.caseCount(tenant, factoryCode));
            rows.addAll(references.cases(tenant, factoryCode, limit));
        }
        var selected =
                rows.stream()
                        .sorted(
                                Comparator.comparing(Reference::getSourceType)
                                        .thenComparing(Reference::getSourceId))
                        .limit(limit)
                        .toList();
        if (selected.size() != Math.min(total, limit))
            throw new CheckedException("排产占用计数与明细不一致，请核对数据");
        return new MdmFactoryPlanningApsReferencesDTO()
                .setTenantId(tenant)
                .setFactoryCode(factoryCode)
                .setTotal(total)
                .setLimit(limit)
                .setTruncated(total > limit)
                .setObservedAt(LocalDateTime.now())
                .setRecords(selected);
    }
}
