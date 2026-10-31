package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesMaterialBindingSupplementEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 追加账必须完整读取；不按删除标记掩盖损坏的历史。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesMaterialBindingSupplementMapper {
    @Select("SELECT mom_mes.mom_material_binding_supplement_ready()")
    Boolean ready();

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_MATERIAL_BINDING_SUPPLEMENT:',#{tenant},':',#{factory},':',#{request}),0))")
    Integer lockRequest(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

    @Select("SELECT * FROM mom_mes.mes_material_binding_supplement WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_id=#{arrange} ORDER BY sequence_no LIMIT 51")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesMaterialBindingSupplementEntity> byArrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") Long arrange);

    @Select("SELECT * FROM mom_mes.mes_material_binding_supplement WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesMaterialBindingSupplementEntity> byRequest(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

    @Insert("INSERT INTO mom_mes.mes_material_binding_supplement(id,tenant_id,factory_code,arrange_id,arrange_code,task_code,produce_task_code,"
            + "route_node_code,product_batch_no,initial_binding_id,sequence_no,previous_fingerprint,request_id,request_hash,preview_fingerprint,"
            + "supplement_json,supplement_fingerprint,operator_id,operator_code,confirmed_at,create_by,create_time,del_flag) VALUES"
            + "(#{id},#{tenantId},#{factoryCode},#{arrangeId},#{arrangeCode},#{taskCode},#{produceTaskCode},#{routeNodeCode},#{productBatchNo},"
            + "#{initialBindingId},#{sequenceNo},#{previousFingerprint},#{requestId},#{requestHash},#{previewFingerprint},"
            + "#{supplementJson},#{supplementFingerprint},#{operatorId},#{operatorCode},#{confirmedAt},#{createBy},#{createTime},#{delFlag})")
    int insert(MesMaterialBindingSupplementEntity row);
}
