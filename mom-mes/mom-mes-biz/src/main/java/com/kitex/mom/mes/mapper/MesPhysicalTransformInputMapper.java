package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalTransformInputEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 正式转换投入行批量写与永久读取。 */
@Mapper
public interface MesPhysicalTransformInputMapper extends BaseMapper<MesPhysicalTransformInputEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_physical_transform_input "
            + "(id,tenant_id,factory_code,execution_id,stock_id,source_report_id,material_code,unit,quantity,before_quantity," 
            + "after_quantity,out_flow_id,source_fingerprint,create_by,create_time,del_flag) SELECT x.id::bigint,x.tenant_id::bigint," 
            + "x.factory_code,x.execution_id::bigint,x.stock_id::bigint,x.source_report_id::bigint,x.material_code,x.unit," 
            + "x.quantity::numeric,x.before_quantity::numeric,x.after_quantity::numeric,x.out_flow_id::bigint,x.source_fingerprint," 
            + "x.create_by,x.create_time::timestamp,'0' FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text," 
            + "tenant_id text,factory_code text,execution_id text,stock_id text,source_report_id text,material_code text,unit text," 
            + "quantity text,before_quantity text,after_quantity text,out_flow_id text,source_fingerprint text,create_by text,create_time text)")
    int insertRows(@Param("rowsJson") String rowsJson);

    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_transform_input WHERE tenant_id=#{tenantId} AND execution_id=#{executionId} ORDER BY stock_id")
    List<MesPhysicalTransformInputEntity> permanentRows(@Param("tenantId") Long tenantId,
                                                        @Param("executionId") Long executionId);
}
