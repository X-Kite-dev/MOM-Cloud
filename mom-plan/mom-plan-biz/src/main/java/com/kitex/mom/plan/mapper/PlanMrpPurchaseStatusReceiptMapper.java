package com.kitex.mom.plan.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlanMrpPurchaseStatusReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlanMrpPurchaseStatusReceiptMapper extends MomBaseMapper<PlanMrpPurchaseStatusReceiptEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('PLAN_MRP_PURCHASE_STATUS:',#{eventId}),0))")
    Integer lockEvent(@Param("eventId") String eventId);
}
