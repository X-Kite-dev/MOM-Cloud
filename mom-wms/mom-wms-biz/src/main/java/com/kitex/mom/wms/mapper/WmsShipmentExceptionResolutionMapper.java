package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.wms.entity.WmsShipmentExceptionResolutionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsShipmentExceptionResolutionMapper extends BaseMapper<WmsShipmentExceptionResolutionEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    int lockKey(String key);
}
