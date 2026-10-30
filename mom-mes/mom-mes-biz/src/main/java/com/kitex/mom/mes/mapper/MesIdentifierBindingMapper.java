package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesIdentifierBindingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 活动实例领域持久层；跨工单外码锁必须在原号和真实工单锁之后按键排序取得。
 */
@Mapper
public interface MesIdentifierBindingMapper extends BaseMapper<MesIdentifierBindingEntity> {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Integer lockIdentity(@Param("key") String key);
}
