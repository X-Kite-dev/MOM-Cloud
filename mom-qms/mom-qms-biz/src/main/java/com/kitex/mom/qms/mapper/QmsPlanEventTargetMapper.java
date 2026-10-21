package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsPlanEventTargetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QmsPlanEventTargetMapper extends BaseMapper<QmsPlanEventTargetEntity> {
    @Select("SELECT * FROM qms_plan_event_target WHERE id=#{id} AND del_flag='0' FOR UPDATE")
    QmsPlanEventTargetEntity lockById(@Param("id") Long id);
}
