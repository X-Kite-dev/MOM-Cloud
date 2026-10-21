package com.kitex.mom.pms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PmsDeliveryCommitmentMapper extends MomBaseMapper<PmsDeliveryCommitmentEntity> {
    @Select("""
            SELECT source.* FROM pms_delivery_commitment source
             WHERE source.del_flag='0' AND source.status='ACTIVE'
               AND NOT EXISTS (
                    SELECT 1 FROM pms_plan_commitment_event_outbox event
                     WHERE event.del_flag='0' AND event.commitment_id=source.id
                       AND event.source_type='PMS_DELIVERY_COMMITMENT_ACTIVATED'
                       AND COALESCE(event.tenant_id,-1)=COALESCE(source.tenant_id,-1))
             ORDER BY source.confirmed_time,source.id LIMIT #{limit}
            """)
    List<PmsDeliveryCommitmentEntity> selectMissingPlanEvents(@Param("limit") int limit);
}
