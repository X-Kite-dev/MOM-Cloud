package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesChangeoverExecutionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesChangeoverExecutionMapper extends BaseMapper<MesChangeoverExecutionEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_CHANGEOVER:', #{deviceCode}), 0))")
    void lockDevice(String deviceCode);
}
