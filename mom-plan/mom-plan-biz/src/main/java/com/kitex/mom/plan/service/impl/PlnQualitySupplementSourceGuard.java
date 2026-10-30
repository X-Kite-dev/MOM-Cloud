package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.plan.entity.PlnFormalPlanContractEntity;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlnQualitySupplementDemandMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 补产数量沿用原产品冻结计量，只核验，不换算历史质量事实。
 */
@Component
@RequiredArgsConstructor
public class PlnQualitySupplementSourceGuard {
    private final PlnQualitySupplementDemandMapper supplementMapper;
    private final RemoteMaterialService remoteMaterialService;

    /**
     * 补产复核是实名写动作，不能退回SYSTEM或跳过租户/工厂上下文。
     */
    public static MomUser requireActor() {
        Long tenantId = TenantContextHolder.getTenantId();
        require(tenantId != null && tenantId > 0 && !TenantContextHolder.getTenantSkip(),
                "补产需求复核必须在实际租户上下文执行");
        require(StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "补产需求复核必须选择实际工厂");
        var authentication = SecurityUtils.getAuthentication();
        MomUser user;
        try {
            user = SecurityUtils.getUser();
        } catch (RuntimeException ex) {
            user = null;
        }
        require(authentication != null && authentication.isAuthenticated()
                        && user != null && user.getId() != null && user.getId() > 0
                        && Objects.equals(tenantId, user.getTenantId()) && StrUtil.isNotBlank(user.getUsername()),
                "补产需求复核必须由当前租户实名用户执行");
        return user;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 复用MDM基本计量，不能默认件或继承另一产品的需求单位。
     */
    public String productUnit(PlnIssueEntity sourceIssue) {
        String sourceUnit = supplementSourceUnit(sourceIssue);
        MdmMaterialMeasureDTO measure;
        try {
            var response = remoteMaterialService.productionMeasure(sourceIssue.getProductCode());
            require(response != null && response.isOk() && response.getData() != null,
                    "补产产品基本计量不可核验，请检查MDM物料档案");
            measure = response.getData();
        } catch (CheckedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CheckedException("补产产品基本计量不可读取，请稍后重试");
        }
        require(StrUtil.isNotBlank(measure.getMaterialId())
                        && Objects.equals(String.valueOf(TenantContextHolder.getTenantId()), measure.getTenantId())
                        && Objects.equals(sourceIssue.getFactoryCode(), measure.getFactoryCode())
                        && Objects.equals(sourceIssue.getProductCode(), measure.getMaterialCode())
                        && Boolean.TRUE.equals(measure.getEnabled()) && StrUtil.isNotBlank(measure.getBasicUnit()),
                "补产产品基本计量的租户、工厂、产品、状态或单位不合法");
        require(Objects.equals(sourceUnit, measure.getBasicUnit()),
                "补产来源单位与当前物料基本单位不一致，须先核对计量，禁止直接换算历史报废数量");
        return sourceUnit;
    }

    /**
     * 只接纳可归属原需求/产品的一致计量；多个历史版本冲突时不猜选。
     */
    private String supplementSourceUnit(PlnIssueEntity sourceIssue) {
        require(StrUtil.isNotBlank(sourceIssue.getSourceDemandNo()), "原生产计划缺少可核验的来源需求，不能推断补产单位");
        Long tenantId = TenantContextHolder.getTenantId();
        List<PlnProductDemandEntity> demands = supplementMapper.sourceDemands(tenantId,
                sourceIssue.getFactoryCode(), sourceIssue.getSourceDemandNo());
        require(demands.size() <= 1, "原生产计划来源需求号不唯一，不能推断补产单位");
        Set<String> sourceUnits = new LinkedHashSet<>();
        for (PlnProductDemandEntity demand : demands) {
            if (Objects.equals(demand.getProductCode(), sourceIssue.getProductCode())
                    && StrUtil.isNotBlank(demand.getProductUnit())) {
                sourceUnits.add(demand.getProductUnit());
            }
        }
        List<PlnFormalPlanContractEntity> contractLines = supplementMapper.sourceProductMeasures(tenantId,
                sourceIssue.getFactoryCode(), sourceIssue.getSourceDemandNo(), sourceIssue.getProductCode());
        require(contractLines.stream().allMatch(line -> StrUtil.isNotBlank(line.getUnitCode())),
                "原产品正式合同存在缺失计量，不能推断补产单位");
        contractLines.forEach(line -> sourceUnits.add(line.getUnitCode()));
        require(sourceUnits.size() == 1, "原产品来源计量缺失或历史单位不一致，不能推断补产单位");
        return sourceUnits.iterator().next();
    }
}
