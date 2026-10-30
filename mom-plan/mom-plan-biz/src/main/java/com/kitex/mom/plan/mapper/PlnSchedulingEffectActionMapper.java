package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.plan.entity.PlnSchedulingEffectActionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 效果偏差行动持久账；行锁用于人工确认与定时重评的并发裁决。
 */
@Mapper
public interface PlnSchedulingEffectActionMapper extends BaseMapper<PlnSchedulingEffectActionEntity> {
    @Select("SELECT * FROM pln_scheduling_effect_action WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    PlnSchedulingEffectActionEntity lockById(@Param("id") Long id);
}
