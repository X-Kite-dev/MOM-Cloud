package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesStartMaterialBindingEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 原账查询不用逻辑删除过滤，损坏或缺表必须报错，不能解释为旧未绑定。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesStartMaterialBindingMapper {
    @Select("SELECT mom_mes.mom_start_material_binding_ready()")
    Boolean ready();

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_START_MATERIAL_BINDING:',#{tenant},':',#{factory},':',#{request}),0))")
    Integer lockRequest(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

    @Select("SELECT * FROM mom_mes.mes_start_material_binding WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_id=#{arrange}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesStartMaterialBindingEntity> byArrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") Long arrange);

    @Select("SELECT * FROM mom_mes.mes_start_material_binding WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesStartMaterialBindingEntity> byRequest(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

    @Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesTaskArrangeEntity arrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0' FOR UPDATE NOWAIT")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesTaskArrangeEntity lockedArrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{code} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesTaskEntity> tasks(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("code") String code);

    @Select("SELECT (SELECT count(*) FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_code=#{arrange})"
            + "+(SELECT count(*) FROM mom_mes.mes_task_report_unqualified WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_code=#{arrange})")
    long reports(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") String arrange);

    @Select("SELECT * FROM mom_mes.mes_warehouse_feed WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_code=#{arrange} ORDER BY id LIMIT 101")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesWarehouseFeedEntity> candidates(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") String arrange);

    @Select("<script>SELECT * FROM mom_mes.mes_warehouse_feed WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id"
            + "<if test='locked'> FOR UPDATE NOWAIT</if></script>")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesWarehouseFeedEntity> selected(@Param("tenant") Long tenant, @Param("factory") String factory,
                                          @Param("ids") List<Long> ids, @Param("locked") boolean locked);

    @Insert("INSERT INTO mom_mes.mes_start_material_binding(id,tenant_id,factory_code,arrange_id,arrange_code,task_code,produce_task_code,"
            + "route_node_code,product_batch_no,request_id,request_hash,preview_fingerprint,binding_json,binding_fingerprint,"
            + "material_gate_record_id,operator_id,operator_code,started_at,create_by,create_time,del_flag) VALUES"
            + "(#{id},#{tenantId},#{factoryCode},#{arrangeId},#{arrangeCode},#{taskCode},#{produceTaskCode},#{routeNodeCode},#{productBatchNo},"
            + "#{requestId},#{requestHash},#{previewFingerprint},#{bindingJson},#{bindingFingerprint},#{materialGateRecordId},"
            + "#{operatorId},#{operatorCode},#{startedAt},#{createBy},#{createTime},#{delFlag})")
    int insert(MesStartMaterialBindingEntity row);
}
