package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionMaterialOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 原生产物料账及真实数据库安装目录；目录查询不读取业务数据。
 */
@Mapper
public interface MesProductionMaterialOperationMapper extends MomBaseMapper<MesProductionMaterialOperationEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_production_material_ready()")
    Boolean ready();
}
