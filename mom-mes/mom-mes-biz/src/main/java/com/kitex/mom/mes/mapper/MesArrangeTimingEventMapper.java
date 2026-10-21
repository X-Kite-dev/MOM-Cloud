package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesArrangeTimingEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;

/** 不可变派工时间事件读取；SQL按真实组织、派工和区间生成完整扣除依据。 */
@Mapper
public interface MesArrangeTimingEventMapper extends MomBaseMapper<MesArrangeTimingEventEntity> {
    @Select("SELECT \"mom_mes\".mom_arrange_timing_pause_context_v1(#{tenant},#{factory},#{arrange},#{from},#{until})::text")
    String pauseContext(@Param("tenant") Long tenant, @Param("factory") String factory,
                        @Param("arrange") Long arrange, @Param("from") LocalDateTime from,
                        @Param("until") LocalDateTime until);
}
