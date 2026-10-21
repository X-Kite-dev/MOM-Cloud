package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalTransformOutputEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 正式转换输出事实批量写与永久读取。 */
@Mapper
public interface MesPhysicalTransformOutputMapper extends BaseMapper<MesPhysicalTransformOutputEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_physical_transform_output "
            + "(id,tenant_id,factory_code,execution_id,output_key,identifier,object_type,disposition,position_code,quantity," 
            + "result_stock_id,in_flow_id,create_by,create_time,del_flag) SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code," 
            + "x.execution_id::bigint,x.output_key,NULLIF(x.identifier,''),x.object_type,x.disposition,NULLIF(x.position_code,'')," 
            + "x.quantity::numeric,NULLIF(x.result_stock_id,'')::bigint,NULLIF(x.in_flow_id,'')::bigint,x.create_by," 
            + "x.create_time::timestamp,'0' FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,tenant_id text," 
            + "factory_code text,execution_id text,output_key text,identifier text,object_type text,disposition text,position_code text," 
            + "quantity text,result_stock_id text,in_flow_id text,create_by text,create_time text)")
    int insertRows(@Param("rowsJson") String rowsJson);

    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_transform_output WHERE tenant_id=#{tenantId} AND execution_id=#{executionId} ORDER BY output_key")
    List<MesPhysicalTransformOutputEntity> permanentRows(@Param("tenantId") Long tenantId,
                                                         @Param("executionId") Long executionId);
}
