package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionMaterialReversalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 恢复永久账与实际目录/原号事务锁；目录和锁查询不读取其他组织业务数据。 */
@Mapper
public interface MesProductionMaterialReversalMapper extends MomBaseMapper<MesProductionMaterialReversalEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_production_material_reversal_ready()")
    Boolean ready();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean tryRequest(@Param("key") String key);
}
