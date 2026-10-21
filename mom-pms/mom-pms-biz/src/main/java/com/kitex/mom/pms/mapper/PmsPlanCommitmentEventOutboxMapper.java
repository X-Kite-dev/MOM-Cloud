package com.kitex.mom.pms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PmsPlanCommitmentEventOutboxMapper extends MomBaseMapper<PmsPlanCommitmentEventOutboxEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PMS_PLAN_COMMITMENT_OUTBOX:', #{key}), 0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT * FROM pms_plan_commitment_event_outbox WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PmsPlanCommitmentEventOutboxEntity lockById(@Param("id") Long id);
}
