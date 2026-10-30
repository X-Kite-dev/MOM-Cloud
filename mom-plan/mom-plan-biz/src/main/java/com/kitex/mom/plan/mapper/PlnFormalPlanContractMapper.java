package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnFormalPlanContractEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 正式计划合同持久化入口。
 *
 * <p>合同事实只能由领域服务新增，不能通过通用更新接口改写。咨询锁用于串行化同一
 * ERP 计划版本或同一 MOM MRP 批次的合同发布。</p>
 */
@Mapper
public interface PlnFormalPlanContractMapper extends MomBaseMapper<PlnFormalPlanContractEntity> {

    /**
     * 获取当前事务范围内的稳定业务锁。
     */
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Integer lock(@Param("lockKey") String lockKey);
}
