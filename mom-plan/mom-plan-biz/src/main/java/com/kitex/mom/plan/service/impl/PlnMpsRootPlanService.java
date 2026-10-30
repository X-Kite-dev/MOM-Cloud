package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnMpsLineEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.mapper.PlnIssueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 普通需求及质量补产共用的MPS桶根计划工厂。调用方持有需求和发布事务锁。
 */
@Component
@RequiredArgsConstructor
public class PlnMpsRootPlanService {
    private final PlnIssueMapper issueMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 每个活动MPS桶形成一条独立根生产计划，APS和MES继续以计划ID/唯一计划号下钻。
     */
    public PlnIssueEntity create(PlnMpsLineEntity line, PlnProductDemandEntity demand) {
        require(Objects.equals(line.getDemandId(), demand.getId())
                && Objects.equals(line.getDemandNo(), demand.getDemandNo())
                && Objects.equals(line.getFactoryCode(), demand.getFactoryCode()), "MPS桶与需求来源不一致");
        PlnIssueEntity existing = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getMpsLineId, line.getId())
                .eq(PlnIssueEntity::getType, "issue"));
        if (existing != null) {
            require(Objects.equals(existing.getSourceDemandNo(), demand.getDemandNo())
                            && Objects.equals(existing.getFactoryCode(), demand.getFactoryCode())
                            && Objects.equals(existing.getProductCode(), demand.getProductCode())
                            && existing.getSourceMrpId() == null
                            && Objects.equals(existing.getIssueNo(), "MPS-" + line.getId())
                            && Objects.equals(existing.getPlanningSourceKey(), line.getBucketKey())
                            && existing.getNum() != null
                            && existing.getNum().compareTo(line.getPlanQuantity()) == 0,
                    "MPS桶已存在载荷不一致的根生产计划：" + line.getBucketKey());
            return existing;
        }
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setIssueNo("MPS-" + line.getId());
        issue.setSourceDemandNo(demand.getDemandNo());
        issue.setPlanningSourceKey(line.getBucketKey());
        issue.setMpsLineId(line.getId());
        issue.setCustomerCode(demand.getCustomerCode());
        issue.setCustomerName(demand.getCustomerName());
        issue.setProductCode(demand.getProductCode());
        issue.setProductName(demand.getProductName());
        issue.setSpecificationModel(demand.getSpecification());
        issue.setNum(line.getPlanQuantity());
        issue.setIssueTime(LocalDateTime.now());
        issue.setDemandTime(line.getPlannedDate());
        issue.setStatus("0");
        issue.setType("issue");
        issue.setFactoryCode(line.getFactoryCode());
        require(issueMapper.insert(issue) == 1, "MPS桶根生产计划创建失败：" + line.getBucketKey());
        return issue;
    }
}
