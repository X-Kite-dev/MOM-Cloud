package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 每条查询显式限定实际组织与软删；不依赖调用方传SQL或当前MDM。 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipNextStationsMapper {
    String OP =
            "id,tenant_id,factory_code,del_flag,task_code,code,mat_code,process_route_id,process_route_code,route_code,route_name,route_execution_schema_version,source_route_node_code,predecessor_node_codes,successor_node_codes,predecessor_codes,successor_codes,node_timing_rules_json,route_order,last_route,overlap_enabled,transfer_batch_quantity,transfer_minutes";
    String ARRANGE =
            OP
                    + ",produce_task_code,status,workshop_code,workshop_name,actual_worksite_code,planned_worksite_code,planned_worksite_name,worksite_codes,planned_start_time,planned_end_time";

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,del_flag,mat_detail_type,source_report_id,produce_task_code,bill_no,mat_code,num"
                + " FROM mom_mes.mes_warehouse_live_stock WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id=#{id} AND del_flag='0' AND"
                + " mat_detail_type='WIP'")
    MesWarehouseLiveStockEntity stock(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,del_flag,status,task_code,produce_task_code,arrange_code,mat_code"
                + " FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    MesTaskReportEntity report(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,del_flag,code,mat_code,process_route_id,process_route_code,engineering_release_id,engineering_version_no,engineering_content_hash,engineering_bundle_snapshot,bom_id,bom_version,rework_request_id,rework_material_plan_id,resource_type"
                + " FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND"
                + " code=#{code} AND del_flag='0' ORDER BY id LIMIT 2")
    List<MesTaskEntity> orders(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("code") String code);

    @Select(
            "SELECT "
                    + OP
                    + " FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_code=#{order} AND del_flag='0' ORDER BY id"
                    + " LIMIT 1001")
    List<MesProduceTaskEntity> operations(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("order") String order);

    @Select(
            "SELECT "
                    + ARRANGE
                    + " FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_code=#{order} AND code=#{code} AND"
                    + " del_flag='0' ORDER BY id LIMIT 2")
    List<MesTaskArrangeEntity> sourceArrange(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("order") String order,
            @Param("code") String code);

    @Select(
            "<script>SELECT "
                    + ARRANGE
                    + " FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND task_code=#{order} AND del_flag='0' AND status"
                    + " IN ('UN_RECEIVE','RECEIVE','PRODUCT','PAUSE') AND produce_task_code IN"
                    + " <foreach collection='operations' item='op' open='(' separator=','"
                    + " close=')'>#{op}</foreach><if test='after != null'> AND id &gt;"
                    + " #{after}</if> ORDER BY id LIMIT 21</script>")
    List<MesTaskArrangeEntity> stations(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("order") String order,
            @Param("operations") List<String> operations,
            @Param("after") Long after);

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,del_flag,source_stock_id,source_report_id,arrange_code,produce_task_code,mat_code,mat_batch,mat_barcode,mat_unit,from_location_code,to_location_code,quantity,remaining_quantity,revision,status,dispatch_user_id,due_time,source_snapshot,location_snapshot"
                + " FROM mom_mes.mes_wip_transfer WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    MesWipTransferEntity transfer(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,del_flag,transfer_id,sequence_no,operation_type,operator_id,request_id,fingerprint,quantity,before_remaining,after_remaining,source_before_num,source_after_num,before_status,after_status,location_code,reason,evidence"
                + " FROM mom_mes.mes_wip_transfer_operation WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND transfer_id=#{id} AND sequence_no=1 ORDER BY id"
                + " LIMIT 2")
    List<MesWipTransferOperationEntity> dispatch(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);
}
