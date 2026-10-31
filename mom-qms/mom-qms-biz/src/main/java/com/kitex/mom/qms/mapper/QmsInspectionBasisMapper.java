package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsInspectionBasisEntity;

import org.apache.ibatis.annotations.*;

@Mapper
public interface QmsInspectionBasisMapper extends BaseMapper<QmsInspectionBasisEntity> {
    @Select("SELECT mom_qms.mom_inspection_basis_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT id FROM mom_qms.qms_inspection_product_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0'")
    Long productParent(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT id FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0'")
    Long procedureParent(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    /** 一次一致性读取整条链；超限时不把大原文传入应用内存。 */
    @Select(
            """
SELECT id,tenant_id,factory_code,inspection_type,task_id,revision,operation,
  previous_fingerprint,payload_fingerprint,created_by,occurred_at,
  CASE WHEN lineage_rows<=5000 AND lineage_bytes<=8388608 THEN payload ELSE NULL END AS payload,
  lineage_rows,lineage_bytes FROM (
    SELECT b.*,COUNT(*) OVER() AS lineage_rows,
      COALESCE(SUM(OCTET_LENGTH(payload)) OVER(),0) AS lineage_bytes
    FROM mom_qms.qms_inspection_basis b WHERE tenant_id=#{tenant} AND factory_code=#{factory}
      AND inspection_type=#{type} AND task_id=#{id}
  ) basis_chain ORDER BY revision ASC LIMIT 5001
""")
    @Results({
        @Result(column = "id", property = "id"),
        @Result(column = "tenant_id", property = "tenantId"),
        @Result(column = "factory_code", property = "factoryCode"),
        @Result(column = "inspection_type", property = "inspectionType"),
        @Result(column = "task_id", property = "taskId"),
        @Result(column = "revision", property = "revision"),
        @Result(column = "operation", property = "operation"),
        @Result(column = "previous_fingerprint", property = "previousFingerprint"),
        @Result(column = "payload", property = "payload"),
        @Result(column = "payload_fingerprint", property = "payloadFingerprint"),
        @Result(column = "created_by", property = "createdBy"),
        @Result(column = "occurred_at", property = "occurredAt"),
        @Result(column = "lineage_rows", property = "lineageRows"),
        @Result(column = "lineage_bytes", property = "lineageBytes")
    })
    java.util.List<QmsInspectionBasisEntity> lineage(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("type") String type,
            @Param("id") Long id);
}
