package com.kitex.mom.mdm.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mdm.entity.MdmBomEcnEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MdmBomEcnMapper extends MomBaseMapper<MdmBomEcnEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockRequest(@Param("key") String key);
}
