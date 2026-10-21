package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.oms.dto.OmsFulfillmentRiskScanCandidate;
import com.kitex.mom.oms.entity.OmsFulfillmentRiskScanStateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OmsFulfillmentRiskScanStateMapper extends MomBaseMapper<OmsFulfillmentRiskScanStateEntity> {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtext(#{key}))")
    Integer lockKey(@Param("key") String key);

    /**
     * 仅独立调度器跨租户发现候选，随后恢复单一租户与工厂上下文。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT d.id AS order_detail_id,d.tenant_id,d.factory_code,d.order_code,d.product_code "
            + "FROM oms_order_details d LEFT JOIN oms_fulfillment_risk_scan_state s "
            + "ON s.tenant_id=d.tenant_id AND s.factory_code=d.factory_code AND s.order_detail_id=d.id AND s.del_flag='0' "
            + "WHERE d.del_flag='0' AND d.tenant_id IS NOT NULL AND d.factory_code IS NOT NULL "
            + "AND d.state IN('3','31','32') AND (s.id IS NULL OR s.status='COMPLETED' OR "
            + "(s.status IN('ACTIVE','ATTENTION') AND s.next_scan_time<=now())) "
            + "ORDER BY s.next_scan_time NULLS FIRST,d.id LIMIT #{limit}")
    List<OmsFulfillmentRiskScanCandidate> dueCandidates(@Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("UPDATE oms_fulfillment_risk_scan_state s SET status='COMPLETED',next_scan_time=NULL,revision=revision+1,update_time=now() "
            + "WHERE s.del_flag='0' AND s.status<>'COMPLETED' AND NOT EXISTS(SELECT 1 FROM oms_order_details d "
            + "WHERE d.id=s.order_detail_id AND d.tenant_id=s.tenant_id AND d.factory_code=s.factory_code "
            + "AND d.del_flag='0' AND d.state IN('3','31','32'))")
    int completeIneligible();
}
