package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 完工判定读取原工单完整集合，人员可见范围和软删均不能隐藏未结束的执行。
 */
@Mapper
public interface MesDispatchCompletionMapper {
    String GRAPH_COLUMNS = "id,tenant_id,factory_code,del_flag,code,task_code,route_code,"
            + "route_execution_schema_version,source_route_node_code,predecessor_node_codes,successor_node_codes,"
            + "node_timing_rules_json,predecessor_codes,successor_codes,route_order,last_route,overlap_enabled,"
            + "transfer_batch_quantity,transfer_minutes,status";

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT " + GRAPH_COLUMNS + ",process_route_id,process_route_code,complete_num,notice_num "
            + "FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} "
            + "AND task_code=#{taskCode} ORDER BY id")
    List<MesProduceTaskEntity> operations(@Param("tenantId") Long tenantId,
                                          @Param("factoryCode") String factoryCode, @Param("taskCode") String taskCode);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT " + GRAPH_COLUMNS + ",produce_task_code FROM mom_mes.mes_task_arrange "
            + "WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND task_code=#{taskCode} ORDER BY id")
    List<MesTaskArrangeEntity> dispatches(@Param("tenantId") Long tenantId,
                                          @Param("factoryCode") String factoryCode, @Param("taskCode") String taskCode);
}
