package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedRestoreLineEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 一次真实feed负向OLD/NEW及全部选定原份额合计；一条固定JSON INSERT提交完整集合。
 */
@Mapper
public interface MesProductionFeedRestoreLineMapper extends BaseMapper<MesProductionFeedRestoreLineEntity> {
    /**
     * 全部原集合一次必要写，真实scope/成员/数量及mutation由数据库独立核。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_production_feed_restore_line (id,tenant_id,factory_code,reversal_id,batch_id,"
            + "consumption_id,feed_id,mat_code,mat_unit,feed_num,restored_num,used_before_num,used_after_num,"
            + "original_consumption_snapshot,create_by,del_flag) SELECT x.\"id\"::bigint,x.\"tenantId\"::bigint,x.\"factoryCode\","
            + "x.\"reversalId\"::bigint,x.\"batchId\"::bigint,x.\"consumptionId\"::bigint,x.\"feedId\"::bigint,x.\"matCode\","
            + "x.\"matUnit\",x.\"feedNum\"::numeric,x.\"restoredNum\"::numeric,x.\"usedBeforeNum\"::numeric,x.\"usedAfterNum\"::numeric,"
            + "x.\"originalConsumptionSnapshot\",x.\"createBy\","
            + "x.\"delFlag\" FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(\"id\" text,\"tenantId\" text,"
            + "\"factoryCode\" text,\"reversalId\" text,\"batchId\" text,\"consumptionId\" text,\"feedId\" text,\"matCode\" text,"
            + "\"matUnit\" text,\"feedNum\" text,\"restoredNum\" text,\"usedBeforeNum\" text,\"usedAfterNum\" text,"
            + "\"originalConsumptionSnapshot\" text,\"createBy\" text,"
            + "\"delFlag\" text) WHERE x.\"tenantId\"=#{tenantId} AND x.\"factoryCode\"=#{factoryCode} AND "
            + "x.\"reversalId\"=#{reversalId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("reversalId") String reversalId);

    /**
     * 精确原账所有子行，不分页、不用逻辑删除隐藏永久原事实。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_restore_line WHERE tenant_id=#{tenantId} AND "
            + "factory_code=#{factoryCode} AND reversal_id=#{reversalId} ORDER BY id")
    List<MesProductionFeedRestoreLineEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                                           @Param("reversalId") Long reversalId);
}
