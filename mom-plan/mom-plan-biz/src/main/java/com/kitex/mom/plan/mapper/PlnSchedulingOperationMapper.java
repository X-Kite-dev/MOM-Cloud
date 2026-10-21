package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnSchedulingOperationMapper extends BaseMapper<PlnSchedulingOperationEntity> {

    /**
     * 正式确认时按资源串行化校验与锁定，避免两个并发任务同时看见“资源空闲”后重复占用。
     * PostgreSQL事务级咨询锁会在当前事务提交或回滚时自动释放。
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtext(#{resourceKey}))")
    Integer acquireResourceTransactionLock(@Param("resourceKey") String resourceKey);
}
