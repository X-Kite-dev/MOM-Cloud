package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesAutoReportRuleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesAutoReportRuleMapper extends MomBaseMapper<MesAutoReportRuleEntity> {
    /**
     * 同一规则的累计计数事件必须串行换算。
     */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_AUTO_REPORT_RULE:', #{ruleId}), 0))")
    void lockRule(@Param("ruleId") Long ruleId);

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockRequest(@Param("key") String key);
}
