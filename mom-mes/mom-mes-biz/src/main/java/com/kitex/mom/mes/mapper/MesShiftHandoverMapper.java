package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesShiftHandover;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesShiftHandoverMapper extends BaseMapper<MesShiftHandover> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lockKey(@Param("key") String key);
}
