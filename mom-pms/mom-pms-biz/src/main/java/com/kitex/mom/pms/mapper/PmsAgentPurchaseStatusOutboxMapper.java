package com.kitex.mom.pms.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PmsAgentPurchaseStatusOutboxMapper extends MomBaseMapper<PmsAgentPurchaseStatusOutboxEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('PMS_AGENT_PURCHASE_STATUS:',#{eventId}),0))")
    void lockEvent(@Param("eventId") String eventId);

    @Select("SELECT * FROM pms_agent_purchase_status_outbox WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PmsAgentPurchaseStatusOutboxEntity lockById(@Param("id") Long id);

    @Select("""
            SELECT source.* FROM pms_order source
             WHERE source.del_flag='0' AND source.source_type='AGENT_SHORTAGE' AND source.status='6'
               AND NOT EXISTS (
                    SELECT 1 FROM pms_agent_purchase_status_outbox event
                     WHERE event.del_flag='0' AND event.pms_order_id=source.id
                       AND event.event_type='AGENT_PURCHASE_ORDER_REJECTED'
                       AND COALESCE(event.tenant_id,-1)=COALESCE(source.tenant_id,-1))
             ORDER BY source.update_time,source.id LIMIT #{limit}
            """)
    List<com.kitex.mom.pms.entity.PmsOrderEntity> selectMissingRejectedOrders(@Param("limit") int limit);

    @Select("""
            SELECT source.* FROM pms_order source
             WHERE source.del_flag='0' AND source.source_type='AGENT_SHORTAGE' AND source.status IN ('7','8')
               AND source.last_change_request_id IS NOT NULL
               AND NOT EXISTS (
                    SELECT 1 FROM pms_agent_purchase_status_outbox event
                     WHERE event.del_flag='0' AND event.pms_order_id=source.id
                       AND event.event_type='AGENT_PURCHASE_ORDER_CANCELLED'
                       AND COALESCE(event.tenant_id,-1)=COALESCE(source.tenant_id,-1))
             ORDER BY source.update_time,source.id LIMIT #{limit}
            """)
    List<com.kitex.mom.pms.entity.PmsOrderEntity> selectMissingCancelledOrders(@Param("limit") int limit);
}
