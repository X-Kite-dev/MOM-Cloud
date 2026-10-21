package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskNoticeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OmsFulfillmentRiskNoticeMapper extends MomBaseMapper<OmsFulfillmentRiskNoticeEntity> {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtext(#{key}))")
    Integer lockKey(@Param("key") String key);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT id,tenant_id,factory_code FROM oms_fulfillment_risk_notice WHERE del_flag='0' AND "
            + "((status IN('PENDING','RETRY') AND next_attempt_time<=now()) OR "
            + "(status='SENDING' AND claimed_time<=now()-interval '10 minutes')) "
            + "ORDER BY COALESCE(next_attempt_time,claimed_time),id LIMIT #{limit}")
    List<OmsFulfillmentRiskNoticeEntity> dueAcrossTenants(@Param("limit") int limit);
}
