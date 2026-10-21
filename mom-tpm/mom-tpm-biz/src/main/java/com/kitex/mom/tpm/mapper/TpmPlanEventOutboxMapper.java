package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.tpm.entity.TpmPlanEventOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TpmPlanEventOutboxMapper extends BaseMapper<TpmPlanEventOutboxEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('TPM_PLAN_OUTBOX:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM tpm_plan_event_outbox WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    TpmPlanEventOutboxEntity lockById(@Param("id") Long id);
}
