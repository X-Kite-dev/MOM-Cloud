package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 反向完成履历和租户级永久人工请求号；与既有唯一键范围一致。
 */
@Mapper
public interface MesPlanCompletionReopenOperationMapper
        extends MomBaseMapper<MesPlanCompletionReopenOperationEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT"
                    + " id,tenant_id,factory_code,outbox_id,action,request_id,before_status,after_status,attempt_no"
                    + " FROM mom_mes.mes_plan_completion_reopen_operation WHERE tenant_id=#{tenant} AND"
                    + " request_id=#{request} ORDER BY id LIMIT 2")
    List<MesPlanCompletionReopenOperationEntity> request(
            @Param("tenant") Long tenant, @Param("request") String request);
}
