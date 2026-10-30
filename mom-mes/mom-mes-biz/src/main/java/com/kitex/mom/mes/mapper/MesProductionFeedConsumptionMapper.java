package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedConsumptionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 一次实际feed增加量的唯一消费根；GOOD/NG份额只引用此根，不复制OLD/NEW；单JSON批量保存，不能将一条实际变动拆成多个事务。
 */
@Mapper
public interface MesProductionFeedConsumptionMapper extends BaseMapper<MesProductionFeedConsumptionEntity> {
    /**
     * 完整本次集合一次INSERT；实际scope、首次事务和原正文membership由数据库独立核验。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_production_feed_consumption (id,tenant_id,factory_code,batch_id,feed_id,"
            + "requirement_mat_code,requirement_unit,mat_code,mat_unit,consume_num,requirement_num,substitute_ratio,"
            + "substitute_flag,feed_num,used_before_num,used_after_num,material_id,material_updated_at,create_by,"
            + "del_flag) SELECT x.\"id\"::bigint,x.\"tenantId\"::bigint,x.\"factoryCode\",x.\"batchId\"::bigint,x.\"feedId\"::bigint,"
            + "x.\"requirementMatCode\",x.\"requirementUnit\",x.\"matCode\",x.\"matUnit\",x.\"consumeNum\"::numeric,"
            + "x.\"requirementNum\"::numeric,x.\"substituteRatio\"::numeric,x.\"substituteFlag\"::boolean,x.\"feedNum\"::numeric,"
            + "x.\"usedBeforeNum\"::numeric,x.\"usedAfterNum\"::numeric,x.\"materialId\"::bigint,x.\"materialUpdatedAt\",x.\"createBy\","
            + "'0' FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(\"id\" text,\"tenantId\" text,\"factoryCode\" text,"
            + "\"batchId\" text,\"feedId\" text,\"requirementMatCode\" text,\"requirementUnit\" text,\"matCode\" text,\"matUnit\" text,"
            + "\"consumeNum\" text,\"requirementNum\" text,\"substituteRatio\" text,\"substituteFlag\" text,\"feedNum\" text,"
            + "\"usedBeforeNum\" text,\"usedAfterNum\" text,\"materialId\" text,\"materialUpdatedAt\" text,"
            + "\"createBy\" text) WHERE x.\"tenantId\"=#{tenantId} AND x.\"factoryCode\"=#{factoryCode} AND x.\"batchId\"=#{batchId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("batchId") String batchId);

    /**
     * 精确原主账全部子行，不分页截断、不按逻辑删除隐藏永久原事实。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_consumption WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} "
            + "AND batch_id=#{batchId} ORDER BY id")
    List<MesProductionFeedConsumptionEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                                           @Param("batchId") Long batchId);
}
