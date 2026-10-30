package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedRestoreShareEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 一条原产出份额的全额恢复认领；不修改旧share；一条固定JSON INSERT提交完整集合。
 */
@Mapper
public interface MesProductionFeedRestoreShareMapper extends BaseMapper<MesProductionFeedRestoreShareEntity> {
    /**
     * 全部原集合一次必要写，真实scope/成员/数量及mutation由数据库独立核。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_production_feed_restore_share (id,tenant_id,factory_code,reversal_id,batch_id,"
            + "original_share_id,output_id,consumption_id,restore_line_id,consume_num,requirement_num,original_share_snapshot,"
            + "create_by,del_flag) SELECT x.\"id\"::bigint,x.\"tenantId\"::bigint,x.\"factoryCode\",x.\"reversalId\"::bigint,"
            + "x.\"batchId\"::bigint,x.\"originalShareId\"::bigint,x.\"outputId\"::bigint,x.\"consumptionId\"::bigint,"
            + "x.\"restoreLineId\"::bigint,x.\"consumeNum\"::numeric,x.\"requirementNum\"::numeric,x.\"originalShareSnapshot\","
            + "x.\"createBy\",x.\"delFlag\" FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(\"id\" text,\"tenantId\" text,"
            + "\"factoryCode\" text,\"reversalId\" text,\"batchId\" text,\"originalShareId\" text,\"outputId\" text,"
            + "\"consumptionId\" text,\"restoreLineId\" text,\"consumeNum\" text,\"requirementNum\" text,\"originalShareSnapshot\" text,"
            + "\"createBy\" text,\"delFlag\" text) WHERE x.\"tenantId\"=#{tenantId} AND x.\"factoryCode\"=#{factoryCode} AND "
            + "x.\"reversalId\"=#{reversalId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("reversalId") String reversalId);

    /**
     * 精确原账所有子行，不分页、不用逻辑删除隐藏永久原事实。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_restore_share WHERE tenant_id=#{tenantId} AND "
            + "factory_code=#{factoryCode} AND reversal_id=#{reversalId} ORDER BY id")
    List<MesProductionFeedRestoreShareEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                                            @Param("reversalId") Long reversalId);
}
