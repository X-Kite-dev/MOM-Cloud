package com.kitex.mom.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.wms.entity.WmsTransferRoutePolicyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WmsTransferRoutePolicyMapper extends BaseMapper<WmsTransferRoutePolicyEntity> {

    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    int lockPolicyDimension(@Param("lockKey") String lockKey);
}
