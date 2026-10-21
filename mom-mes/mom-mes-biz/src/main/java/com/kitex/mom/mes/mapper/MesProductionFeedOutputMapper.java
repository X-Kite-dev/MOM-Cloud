package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedOutputEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 独立总耗料的一份真实GOOD或NG产出；两个报告主键域严格分开；单JSON批量保存，不能将一条实际变动拆成多个事务。 */
@Mapper
public interface MesProductionFeedOutputMapper extends BaseMapper<MesProductionFeedOutputEntity> {
    /** 完整本次集合一次INSERT；实际scope、首次事务和原正文membership由数据库独立核验。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_production_feed_output (id,tenant_id,factory_code,batch_id,output_key,output_kind,"
            + "good_report_id,ng_report_id,source_birth_id,quantity,production_unit,source_snapshot,create_by,"
            + "del_flag) SELECT x.\"id\"::bigint,x.\"tenantId\"::bigint,x.\"factoryCode\",x.\"batchId\"::bigint,x.\"outputKey\","
            + "x.\"outputKind\",x.\"goodReportId\"::bigint,x.\"ngReportId\"::bigint,x.\"sourceBirthId\"::bigint,x.\"quantity\"::numeric,"
            + "x.\"productionUnit\",x.\"sourceSnapshot\",x.\"createBy\",'0' FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS "
            + "x(\"id\" text,\"tenantId\" text,\"factoryCode\" text,\"batchId\" text,\"outputKey\" text,\"outputKind\" text,"
            + "\"goodReportId\" text,\"ngReportId\" text,\"sourceBirthId\" text,\"quantity\" text,\"productionUnit\" text,"
            + "\"sourceSnapshot\" text,\"createBy\" text) WHERE x.\"tenantId\"=#{tenantId} AND x.\"factoryCode\"=#{factoryCode} AND "
            + "x.\"batchId\"=#{batchId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("batchId") String batchId);

    /** 精确原主账全部子行，不分页截断、不按逻辑删除隐藏永久原事实。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_output WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND "
            + "batch_id=#{batchId} ORDER BY id")
    List<MesProductionFeedOutputEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                       @Param("batchId") Long batchId);
}
