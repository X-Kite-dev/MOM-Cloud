package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalTransformAllocationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 正式转换多来源分配边使用单JSON参数批量写入，避免50000边参数展开。
 */
@Mapper
public interface MesPhysicalTransformAllocationMapper extends BaseMapper<MesPhysicalTransformAllocationEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_physical_transform_allocation "
            + "(id,tenant_id,factory_code,execution_id,input_id,output_id,stock_id,output_key,equivalent_quantity,create_by,create_time,del_flag) "
            + "SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code,x.execution_id::bigint,x.input_id::bigint,x.output_id::bigint,"
            + "x.stock_id::bigint,x.output_key,x.equivalent_quantity::numeric,x.create_by,x.create_time::timestamp,'0' "
            + "FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,tenant_id text,factory_code text,execution_id text,"
            + "input_id text,output_id text,stock_id text,output_key text,equivalent_quantity text,create_by text,create_time text)")
    int insertRows(@Param("rowsJson") String rowsJson);

    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_transform_allocation WHERE tenant_id=#{tenantId} AND execution_id=#{executionId} ORDER BY stock_id,output_key")
    List<MesPhysicalTransformAllocationEntity> permanentRows(@Param("tenantId") Long tenantId,
                                                             @Param("executionId") Long executionId);
}
