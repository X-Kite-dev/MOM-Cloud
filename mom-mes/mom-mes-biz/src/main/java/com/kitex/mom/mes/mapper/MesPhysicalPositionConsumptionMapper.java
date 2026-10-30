package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalPositionConsumptionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 已确认位置的永久消费结果；只由正式执行事务批量写入。
 */
@Mapper
public interface MesPhysicalPositionConsumptionMapper extends BaseMapper<MesPhysicalPositionConsumptionEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_physical_position_consumption "
            + "(id,tenant_id,factory_code,survey_id,position_code,execution_id,output_id,disposition,output_identifier,"
            + "create_by,create_time,del_flag) SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code,x.survey_id::bigint,"
            + "x.position_code,x.execution_id::bigint,x.output_id::bigint,x.disposition,NULLIF(x.output_identifier,''),"
            + "x.create_by,x.create_time::timestamp,'0' FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,"
            + "tenant_id text,factory_code text,survey_id text,position_code text,execution_id text,output_id text,"
            + "disposition text,output_identifier text,create_by text,create_time text)")
    int insertRows(@Param("rowsJson") String rowsJson);
}
