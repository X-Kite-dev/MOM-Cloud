package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnQualitySupplementWithdrawalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlnQualitySupplementWithdrawalMapper
        extends MomBaseMapper<PlnQualitySupplementWithdrawalEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_QSUP_WITHDRAW:', #{key}), 0))")
    void lockKey(@Param("key") String key);
}
