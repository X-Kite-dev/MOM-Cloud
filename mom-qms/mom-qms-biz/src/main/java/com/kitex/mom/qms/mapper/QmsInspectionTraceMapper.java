package com.kitex.mom.qms.mapper;

import com.kitex.mom.qms.dto.QmsInspectionTraceRows.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** Bounded scoped projections only; no task mutation methods. */
@Mapper
public interface QmsInspectionTraceMapper {
    @Select(
            """
<script>
SELECT id,tenant_id,factory_code,source_task_code,source_request_id,order_no,mat_code,mat_name,mat_spec,process_code,process_name,batch_no,standard_id,standard_name,dept_code,num,inspection_num,status,result FROM mom_qms.qms_inspection_product_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' AND id IN <foreach collection="ids" item="key" open="(" separator="," close=")">#{key}</foreach> ORDER BY id LIMIT 129
</script>
""")
    @Results(
            id = "traceTask",
            value = {
                @Result(column = "id", property = "id"),
                @Result(column = "tenant_id", property = "tenantId"),
                @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "source_task_code", property = "sourceTaskCode"),
                @Result(column = "source_request_id", property = "sourceRequestId"),
                @Result(column = "order_no", property = "orderNo"),
                @Result(column = "mat_code", property = "matCode"),
                @Result(column = "mat_name", property = "matName"),
                @Result(column = "mat_spec", property = "matSpec"),
                @Result(column = "process_code", property = "processCode"),
                @Result(column = "process_name", property = "processName"),
                @Result(column = "batch_no", property = "batchNo"),
                @Result(column = "standard_id", property = "standardId"),
                @Result(column = "standard_name", property = "standardName"),
                @Result(column = "dept_code", property = "deptCode"),
                @Result(column = "num", property = "num"),
                @Result(column = "inspection_num", property = "inspectionNum"),
                @Result(column = "status", property = "status"),
                @Result(column = "result", property = "result")
            })
    List<Task> productTasks(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") List<Long> ids);

    @Select(
            """
<script>
SELECT id,tenant_id,factory_code,source_task_code,source_request_id,order_no,mat_code,mat_name,mat_spec,process_code,process_name,batch_no,standard_id,standard_name,dept_code,num,inspection_num,status,result FROM mom_qms.qms_inspection_procedure_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' AND id IN <foreach collection="ids" item="key" open="(" separator="," close=")">#{key}</foreach> ORDER BY id LIMIT 129
</script>
""")
    @ResultMap("traceTask")
    List<Task> procedureTasks(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") List<Long> ids);

    @Select(
            """
<script>
SELECT id,tenant_id,factory_code,task_id,handle_num,handle_result,handle_remark FROM mom_qms.qms_inspection_product_handle_record WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' AND id IN <foreach collection="ids" item="key" open="(" separator="," close=")">#{key}</foreach> ORDER BY id LIMIT 129
</script>
""")
    @Results(
            id = "traceHandle",
            value = {
                @Result(column = "id", property = "id"),
                @Result(column = "tenant_id", property = "tenantId"),
                @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "task_id", property = "taskId"),
                @Result(column = "handle_num", property = "handleNum"),
                @Result(column = "handle_result", property = "handleResult"),
                @Result(column = "handle_remark", property = "handleRemark")
            })
    List<Handle> productHandles(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") List<Long> ids);

    @Select(
            """
<script>
SELECT id,tenant_id,factory_code,task_id,handle_num,handle_result,handle_remark FROM mom_qms.qms_inspection_procedure_handle_record WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' AND id IN <foreach collection="ids" item="key" open="(" separator="," close=")">#{key}</foreach> ORDER BY id LIMIT 129
</script>
""")
    @ResultMap("traceHandle")
    List<Handle> procedureHandles(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("ids") List<Long> ids);

    @Select(
            """
<script>
SELECT id,del_flag,tenant_id,factory_code,inspection_type,request_id,inspection_task_id,handle_record_id,original_mes_task_code,original_mes_produce_task_code,rework_process_code,mat_code,mat_name,mat_spec,batch_no,quantity,reason,status,mes_task_code,mes_completed_quantity,reinspection_task_id,reinspection_status FROM mom_qms.qms_rework_order WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND inspection_type=#{type} AND (inspection_task_id IN <foreach collection="ids" item="key" open="(" separator="," close=")">#{key}</foreach> OR reinspection_task_id IN <foreach collection="ids" item="key" open="(" separator="," close=")">#{key}</foreach>) ORDER BY id LIMIT 129
</script>
""")
    @Results(
            id = "traceOrder",
            value = {
                @Result(column = "id", property = "id"),
                @Result(column = "del_flag", property = "delFlag"),
                @Result(column = "tenant_id", property = "tenantId"),
                @Result(column = "factory_code", property = "factoryCode"),
                @Result(column = "inspection_type", property = "inspectionType"),
                @Result(column = "request_id", property = "requestId"),
                @Result(column = "inspection_task_id", property = "inspectionTaskId"),
                @Result(column = "handle_record_id", property = "handleRecordId"),
                @Result(column = "original_mes_task_code", property = "originalMesTaskCode"),
                @Result(
                        column = "original_mes_produce_task_code",
                        property = "originalMesProduceTaskCode"),
                @Result(column = "rework_process_code", property = "reworkProcessCode"),
                @Result(column = "mat_code", property = "matCode"),
                @Result(column = "mat_name", property = "matName"),
                @Result(column = "mat_spec", property = "matSpec"),
                @Result(column = "batch_no", property = "batchNo"),
                @Result(column = "quantity", property = "quantity"),
                @Result(column = "reason", property = "reason"),
                @Result(column = "status", property = "status"),
                @Result(column = "mes_task_code", property = "mesTaskCode"),
                @Result(column = "mes_completed_quantity", property = "mesCompletedQuantity"),
                @Result(column = "reinspection_task_id", property = "reinspectionTaskId"),
                @Result(column = "reinspection_status", property = "reinspectionStatus")
            })
    List<Order> orders(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("type") String type,
            @Param("ids") List<Long> ids);
}
