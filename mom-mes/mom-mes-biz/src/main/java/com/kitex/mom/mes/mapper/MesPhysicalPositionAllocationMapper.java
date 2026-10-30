package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalPositionAllocationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 原位置至全部真实库存的当量边；不是转换扣减或产出分配；永久查询不以逻辑删除隐藏原历史。
 */
@Mapper
public interface MesPhysicalPositionAllocationMapper extends BaseMapper<MesPhysicalPositionAllocationEntity> {
    /**
     * 全部位置来源边单JSON参数/单INSERT，不为50000边展开数十万绑定参数或拆成独立事务。
     * 每行scope/首次事务与永久唯一仍有效；即时集合membership由SQL255在语句返回前核验。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_physical_position_allocation "
            + "(id,tenant_id,factory_code,survey_id,position_code,stock_id,equivalent_quantity,create_by,create_time,del_flag) "
            + "SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code,x.survey_id::bigint,x.position_code,x.stock_id::bigint,"
            + "x.equivalent_quantity::numeric,x.create_by,x.create_time::timestamp,'0' "
            + "FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,tenant_id text,factory_code text,survey_id text,"
            + "position_code text,stock_id text,equivalent_quantity text,create_by text,create_time text) "
            + "WHERE x.tenant_id=#{tenantId} AND x.factory_code=#{factoryCode} AND x.survey_id=#{surveyId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("surveyId") String surveyId);

    /**
     * 原主账全部子事实，服务层核实际工厂和完整集合，不分页截断。
     */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_position_allocation WHERE tenant_id=#{tenantId} AND survey_id=#{surveyId} ORDER BY id")
    List<MesPhysicalPositionAllocationEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("surveyId") Long surveyId);
}
