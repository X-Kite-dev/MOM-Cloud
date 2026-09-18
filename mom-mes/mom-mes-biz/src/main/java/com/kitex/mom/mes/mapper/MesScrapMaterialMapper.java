package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesScrapMaterialEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 报废品管理Mapper
 *
 * @author system
 * @since 2025-01-27
 */
@Mapper
public interface MesScrapMaterialMapper extends BaseMapper<MesScrapMaterialEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(#{requestId}, 0))")
    Integer lockRequest(@Param("requestId") String requestId);
}
