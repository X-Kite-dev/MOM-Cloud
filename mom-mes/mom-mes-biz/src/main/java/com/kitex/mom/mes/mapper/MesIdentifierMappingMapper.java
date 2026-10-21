package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesIdentifierMappingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 生产标识映射数据访问。
 */
@Mapper
public interface MesIdentifierMappingMapper extends MomBaseMapper<MesIdentifierMappingEntity> {

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_IDENTIFIER_MAPPING:', #{factoryCode}, ':', #{mappingCode}), 0))")
    Integer lockMapping(@Param("factoryCode") String factoryCode, @Param("mappingCode") String mappingCode);
}
