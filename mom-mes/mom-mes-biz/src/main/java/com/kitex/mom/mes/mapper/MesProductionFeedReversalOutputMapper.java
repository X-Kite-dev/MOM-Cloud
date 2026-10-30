package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedReversalOutputEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 永久唯一选定产出；零需求也必须一次认领；一条固定JSON INSERT提交完整集合。
 */
@Mapper
public interface MesProductionFeedReversalOutputMapper extends BaseMapper<MesProductionFeedReversalOutputEntity> {
    /**
     * 全部原集合一次必要写，真实scope/成员/数量及mutation由数据库独立核。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_production_feed_reversal_output (id,tenant_id,factory_code,reversal_id,batch_id,"
            + "output_id,original_output_snapshot,create_by,del_flag) SELECT x.\"id\"::bigint,x.\"tenantId\"::bigint,"
            + "x.\"factoryCode\",x.\"reversalId\"::bigint,x.\"batchId\"::bigint,x.\"outputId\"::bigint,x.\"originalOutputSnapshot\","
            + "x.\"createBy\",x.\"delFlag\" FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(\"id\" text,\"tenantId\" text,"
            + "\"factoryCode\" text,\"reversalId\" text,\"batchId\" text,\"outputId\" text,\"originalOutputSnapshot\" text,"
            + "\"createBy\" text,\"delFlag\" text) WHERE x.\"tenantId\"=#{tenantId} AND x.\"factoryCode\"=#{factoryCode} AND "
            + "x.\"reversalId\"=#{reversalId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("reversalId") String reversalId);

    /**
     * 精确原账所有子行，不分页、不用逻辑删除隐藏永久原事实。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_reversal_output WHERE tenant_id=#{tenantId} AND "
            + "factory_code=#{factoryCode} AND reversal_id=#{reversalId} ORDER BY id")
    List<MesProductionFeedReversalOutputEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                                              @Param("reversalId") Long reversalId);
}
