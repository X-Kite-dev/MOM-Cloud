package com.kitex.mom.pms.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PmsPlanMapper extends MomBaseMapper<PmsPlanEntity> {

    List<PmsPlanEntity> getPmsPlanList(@Param(Constants.WRAPPER) Wrapper<PmsPlanEntity> wrapper);

    /**
     * 同事务内按业务键串行化来源创建和计划变更，避免空结果上的FOR UPDATE并发穿透。
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockKey(@Param("key") String key);
}
