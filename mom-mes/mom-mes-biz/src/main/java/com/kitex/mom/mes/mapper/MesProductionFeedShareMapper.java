package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedShareEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 真实耗用根分配到逐产出的永久份额；用于独立恢复而非总量比例舍入；单JSON批量保存，不能将一条实际变动拆成多个事务。
 */
@Mapper
public interface MesProductionFeedShareMapper extends BaseMapper<MesProductionFeedShareEntity> {
    /**
     * 完整本次集合一次INSERT；实际scope、首次事务和原正文membership由数据库独立核验。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Insert("INSERT INTO \"mom_mes\".mes_production_feed_share (id,tenant_id,factory_code,batch_id,output_id,consumption_id,"
            + "consume_num,requirement_num,create_by,del_flag) SELECT x.\"id\"::bigint,x.\"tenantId\"::bigint,x.\"factoryCode\","
            + "x.\"batchId\"::bigint,x.\"outputId\"::bigint,x.\"consumptionId\"::bigint,x.\"consumeNum\"::numeric,"
            + "x.\"requirementNum\"::numeric,x.\"createBy\",'0' FROM jsonb_to_recordset(CAST(#{rowsJson} AS jsonb)) AS x(\"id\" text,"
            + "\"tenantId\" text,\"factoryCode\" text,\"batchId\" text,\"outputId\" text,\"consumptionId\" text,\"consumeNum\" text,"
            + "\"requirementNum\" text,\"createBy\" text) WHERE x.\"tenantId\"=#{tenantId} AND x.\"factoryCode\"=#{factoryCode} AND "
            + "x.\"batchId\"=#{batchId}")
    int insertFrozenRows(@Param("rowsJson") String rowsJson, @Param("tenantId") String tenantId,
                         @Param("factoryCode") String factoryCode, @Param("batchId") String batchId);

    /**
     * 精确原主账全部子行，不分页截断、不按逻辑删除隐藏永久原事实。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_share WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND "
            + "batch_id=#{batchId} ORDER BY id")
    List<MesProductionFeedShareEntity> permanentRows(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                                                     @Param("batchId") Long batchId);
}
