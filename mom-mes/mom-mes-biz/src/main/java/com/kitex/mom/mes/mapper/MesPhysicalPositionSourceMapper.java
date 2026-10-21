package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesPhysicalPositionSourceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 位置观察的每条精确原库存；不合并来源或制造库存；永久查询不以逻辑删除隐藏原历史。 */
@Mapper
public interface MesPhysicalPositionSourceMapper extends BaseMapper<MesPhysicalPositionSourceEntity> {
    /**
     * 服务器生成的全部精确来源一次写入，不向客户端开放记录集。
     * 单JSON参数避免大批量绑定上限；实际组织/原主号显式过滤，必要写数由服务回核。
     * SQL255的即时statement membership及原永久提交证明同时生效，不能退回逐行网络提交。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_physical_position_source "
            + "(id,tenant_id,factory_code,survey_id,stock_id,source_report_id,source_version,source_snapshot,source_fingerprint,"
            + "native_quantity,native_unit,equivalent_quantity,create_by,create_time,del_flag) "
            + "SELECT x.id::bigint,x.tenant_id::bigint,x.factory_code,x.survey_id::bigint,x.stock_id::bigint,"
            + "x.source_report_id::bigint,x.source_version,x.source_snapshot,x.source_fingerprint,"
            + "x.native_quantity::numeric,x.native_unit,x.equivalent_quantity::numeric,x.create_by,x.create_time::timestamp,'0' "
            + "FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(id text,tenant_id text,factory_code text,survey_id text,"
            + "stock_id text,source_report_id text,source_version text,source_snapshot text,source_fingerprint text,"
            + "native_quantity text,native_unit text,equivalent_quantity text,create_by text,create_time text) "
            + "WHERE x.tenant_id=#{tenantId} AND x.factory_code=#{factoryCode} AND x.survey_id=#{surveyId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("surveyId") String surveyId);

    /** 原主账全部子事实，服务层核实际工厂和完整集合，不分页截断。 */
    @InterceptorIgnore(dataPermission = "true")
    @Select("SELECT * FROM mes_physical_position_source WHERE tenant_id=#{tenantId} AND survey_id=#{surveyId} ORDER BY id")
    List<MesPhysicalPositionSourceEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("surveyId") Long surveyId);
}
