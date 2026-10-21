package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesStartPreparationCheck;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 快照永久请求串行化；事务锁不承担真实派工开工锁语义。 */
@Mapper
public interface MesStartPreparationCheckMapper extends BaseMapper<MesStartPreparationCheck> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    void lockRequest(@Param("key") String key);
}
