package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesPlanWithdrawalReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesPlanWithdrawalReceiptMapper extends MomBaseMapper<MesPlanWithdrawalReceiptEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_PLAN_WITHDRAW:', #{key}), 0))")
    Integer lockKey(@Param("key") String key);
}
