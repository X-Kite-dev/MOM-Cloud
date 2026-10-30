package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PlnAutomationEventMapper extends BaseMapper<PlnAutomationEventEntity> {
    @Select(
            "SELECT 1 AS locked FROM"
                    + " pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_AUTOMATION_EVENT:', #{key}),"
                    + " 0))")
    Integer lockKey(@Param("key") String key);

    @Select("SELECT * FROM pln_automation_event WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PlnAutomationEventEntity lockById(@Param("id") Long id);

    /**
     * 后台只领取候选身份；业务执行重新进入该行实际组织并加锁读取。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code,event_no FROM mom_plan.pln_automation_event WHERE"
                    + " del_flag='0' AND status IN('RECEIVED','RETRY_WAIT') AND"
                    + " next_attempt_time<=CURRENT_TIMESTAMP ORDER BY priority,next_attempt_time,id"
                    + " LIMIT #{limit}")
    List<PlnAutomationEventEntity> due(@Param("limit") int limit);
}
