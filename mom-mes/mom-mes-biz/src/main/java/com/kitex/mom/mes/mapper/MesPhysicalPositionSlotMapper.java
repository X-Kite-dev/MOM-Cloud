package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalPositionSlotEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 原完整布局逐位观察；不得将存在状态当质量状态或可消费余额；永久查询不以逻辑删除隐藏原历史。 */
@Mapper
public interface MesPhysicalPositionSlotMapper extends BaseMapper<MesPhysicalPositionSlotEntity> {
    /** 完整位置单语句写入；身份/审计仅服务器提供，UNKNOWN的实物计数保持SQL NULL，不默认为1。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_physical_position_slot "
            + "(id,tenant_id,factory_code,survey_id,position_code,presence,physical_count,create_by,create_time,del_flag) "
            + "SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code,x.survey_id::bigint,x.position_code,x.presence,"
            + "x.physical_count::bigint,x.create_by,x.create_time::timestamp,'0' "
            + "FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,tenant_id text,factory_code text,survey_id text,"
            + "position_code text,presence text,physical_count text,create_by text,create_time text) "
            + "WHERE x.tenant_id=#{tenantId} AND x.factory_code=#{factoryCode} AND x.survey_id=#{surveyId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("surveyId") String surveyId);

    /** 原主账全部子事实，服务层核实际工厂和完整集合，不分页截断。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_position_slot WHERE tenant_id=#{tenantId} AND survey_id=#{surveyId} ORDER BY id")
    List<MesPhysicalPositionSlotEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("surveyId") Long surveyId);
}
