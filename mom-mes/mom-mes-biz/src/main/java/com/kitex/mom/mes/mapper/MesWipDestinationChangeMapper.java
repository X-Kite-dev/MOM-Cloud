package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.*;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/** 每条语句显式限定租户、实际工厂及软删；只关联精确库存主键。 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipDestinationChangeMapper {
    record Totals(
            BigDecimal quantity,
            long receiptCount,
            long stockCount,
            long quantityCount,
            BigDecimal minimum) {}

    @Select(
            "SELECT COALESCE(SUM(quantity),0) AS quantity,COUNT(*) AS receipt_count,COUNT(DISTINCT"
                + " result_stock_id) AS stock_count,COUNT(quantity) AS quantity_count,MIN(quantity)"
                + " AS minimum FROM mom_mes.mes_wip_transfer_operation WHERE tenant_id=#{tenant}"
                + " AND factory_code=#{factory} AND transfer_id=#{id} AND"
                + " operation_type='RECEIVE_RETURN' AND del_flag='0'")
    Totals totals(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT "
                    + OPERATION
                    + " FROM mom_mes.mes_wip_transfer_operation WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND transfer_id=#{id} AND"
                    + " operation_type='REQUEST_RETURN' AND del_flag='0' ORDER BY id LIMIT 2")
    List<MesWipTransferOperationEntity> returnRequest(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    String TRANSFER =
            "id,tenant_id,factory_code,del_flag,source_stock_id,source_report_id,arrange_code,produce_task_code,mat_code,mat_batch,mat_barcode,mat_unit,from_location_code,to_location_code,quantity,remaining_quantity,received_quantity,returned_quantity,quarantined_quantity,lost_quantity,revision,status,indivisible,resume_status,dispatch_user_id,return_user_id,due_time,source_snapshot,location_snapshot";
    String OPERATION =
            "id,tenant_id,factory_code,del_flag,transfer_id,sequence_no,operation_type,operator_id,request_id,fingerprint,quantity,before_remaining,after_remaining,source_before_num,source_after_num,before_status,after_status,location_code,reason,evidence,result_stock_id";

    @Select(
            "SELECT "
                    + TRANSFER
                    + " FROM mom_mes.mes_wip_transfer WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    MesWipTransferEntity transfer(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT "
                    + OPERATION
                    + " FROM mom_mes.mes_wip_transfer_operation WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND transfer_id=#{id} AND sequence_no=1 AND"
                    + " del_flag='0' ORDER BY id LIMIT 2")
    List<MesWipTransferOperationEntity> dispatch(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "<script>SELECT "
                    + OPERATION
                    + " FROM mom_mes.mes_wip_transfer_operation WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND transfer_id=#{id} AND"
                    + " operation_type='RECEIVE_RETURN' AND del_flag='0'<if test='after != null'>"
                    + " AND id &gt; #{after}</if> ORDER BY id LIMIT 21</script>")
    List<MesWipTransferOperationEntity> receipts(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("after") Long after);

    String STOCK =
            "id,tenant_id,factory_code,del_flag,source_report_id,produce_task_code,bill_no,mat_detail_type,mat_code,mat_batch,mat_barcode,mat_unit,num,warehouse_code,physical_location_code,status,inspection_status,judge_status,quality_case_id,mat_valid_end_date";

    @Select(
            "<script>SELECT "
                    + STOCK
                    + " FROM mom_mes.mes_warehouse_live_stock WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND del_flag='0' AND <choose><when test='ids !="
                    + " null and ids.size() > 0 and ids.size() &lt;= 20'>id IN <foreach"
                    + " collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach></when><otherwise>1=0</otherwise></choose> ORDER"
                    + " BY id</script>")
    List<MesWarehouseLiveStockEntity> stocks(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") List<Long> ids);

    @Select(
            "<script>SELECT "
                    + TRANSFER
                    + " FROM (SELECT "
                    + TRANSFER
                    + ",ROW_NUMBER() OVER(PARTITION BY source_stock_id ORDER BY id DESC) AS"
                    + " issue_rank FROM mom_mes.mes_wip_transfer WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND del_flag='0' AND <choose><when test='ids !="
                    + " null and ids.size() > 0 and ids.size() &lt;= 20'>source_stock_id IN"
                    + " <foreach collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach></when><otherwise>1=0</otherwise></choose>) ranked"
                    + " WHERE issue_rank &lt;= 6 ORDER BY source_stock_id,id DESC</script>")
    List<MesWipTransferEntity> issuesForStocks(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") List<Long> ids);

    @Select(
            "<script>SELECT "
                    + OPERATION
                    + " FROM (SELECT "
                    + OPERATION
                    + ",ROW_NUMBER() OVER(PARTITION BY transfer_id ORDER BY id) AS origin_rank FROM"
                    + " mom_mes.mes_wip_transfer_operation WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND del_flag='0' AND sequence_no=1 AND"
                    + " <choose><when test='ids != null and ids.size() > 0 and ids.size() &lt;="
                    + " 100'>transfer_id IN <foreach collection='ids' item='id' open='('"
                    + " separator=','"
                    + " close=')'>#{id}</foreach></when><otherwise>1=0</otherwise></choose>) ranked"
                    + " WHERE origin_rank &lt;= 2 ORDER BY transfer_id,id</script>")
    List<MesWipTransferOperationEntity> dispatches(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") List<Long> ids);
}
