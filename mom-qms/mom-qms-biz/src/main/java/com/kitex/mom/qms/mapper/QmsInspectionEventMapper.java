package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsInspectionEventEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface QmsInspectionEventMapper extends BaseMapper<QmsInspectionEventEntity> {
    String PARENT_COLUMNS =
            """
id AS "id",tenant_id AS "tenantId",factory_code AS "factoryCode",
source_task_code AS "sourceTaskCode",source_request_id AS "sourceRequestId",
mat_code AS "matCode",process_code AS "processCode",standard_id AS "standardId",
batch_no AS "batchNo",num AS "num",inspection_num AS "inspectionNum",status AS "status",
result AS "result",usable_num AS "usableNum",handle_num AS "handleNum",
handle_result AS "handleResult",order_no AS "orderNo",order_id AS "orderId",
mat_barcode AS "matBarcode",dept_code AS "deptCode",
latest_inspection_time AS "latestInspectionTime",latest_append_time AS "latestAppendTime"
""";
    String HISTORY_COLUMNS =
            """
            id AS "id",tenant_id AS "tenantId",factory_code AS "factoryCode",task_id AS "taskId",
            type AS "type",num AS "num",time AS "time",qualified_status AS "qualifiedStatus",
            cancel_reason AS "cancelReason",create_by AS "createBy"
            """;

    @Select("SELECT mom_qms.mom_inspection_event_ready_v1()")
    Integer schemaVersion();

    @Select(
            """
SELECT COUNT(*) AS "event_count",COALESCE(SUM(OCTET_LENGTH(payload)),0) AS "payload_bytes"
FROM mom_qms.qms_inspection_event WHERE tenant_id=#{tenant} AND factory_code=#{factory}
AND inspection_type=#{type} AND task_id=#{taskId}
""")
    Map<String, Object> footprint(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("type") String type,
            @Param("taskId") Long taskId);

    @Select(
            "SELECT "
                    + HISTORY_COLUMNS
                    + " FROM mom_qms.qms_inspection_procedure_history WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    Map<String, Object> procedureHistory(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT "
                    + HISTORY_COLUMNS
                    + " FROM mom_qms.qms_inspection_product_history WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    Map<String, Object> productHistory(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "<script>SELECT "
                    + PARENT_COLUMNS
                    + ",source_request_fingerprint AS \"sourceRequestFingerprint\",label_type AS"
                    + " \"labelType\" FROM mom_qms.qms_inspection_procedure_task WHERE"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND"
                    + " del_flag='0' <if test='lock'>FOR UPDATE</if></script>")
    Map<String, Object> procedureSnapshot(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("lock") boolean lock);

    @Select(
            "<script>SELECT "
                    + PARENT_COLUMNS
                    + " FROM mom_qms.qms_inspection_product_task WHERE tenant_id=#{tenant}"
                    + " AND factory_code=#{factory} AND id=#{id} AND del_flag='0'"
                    + " <if test='lock'>FOR UPDATE</if></script>")
    Map<String, Object> productSnapshot(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("id") Long id,
            @Param("lock") boolean lock);
}
