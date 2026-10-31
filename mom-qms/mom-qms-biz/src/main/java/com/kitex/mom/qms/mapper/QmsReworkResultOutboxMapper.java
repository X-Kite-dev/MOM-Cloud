package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.entity.QmsReworkResultOutboxEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QmsReworkResultOutboxMapper extends BaseMapper<QmsReworkResultOutboxEntity> {
    @Select("SELECT mom_qms.mom_rework_result_outbox_ready_v1()")
    Integer schemaVersion();

    @Select(
            "SELECT * FROM mom_qms.qms_rework_order WHERE id=#{id} AND tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND del_flag='0' FOR UPDATE")
    QmsReworkOrderEntity lockOrder(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0'")
    QmsInspectionProcedureTaskEntity procedure(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_product_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0'")
    QmsInspectionProductTaskEntity product(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    // 仅发现后台候选；不返回原文，投递时恢复单组织并重新加锁核验。
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select(
            "SELECT id,tenant_id,factory_code FROM mom_qms.qms_rework_result_outbox q WHERE status"
                    + " IN('PENDING','RETRY_WAIT') AND next_attempt_time<=CURRENT_TIMESTAMP AND NOT"
                    + " EXISTS(SELECT 1 FROM mom_qms.qms_rework_result_outbox p WHERE"
                    + " p.tenant_id=q.tenant_id AND p.factory_code=q.factory_code AND"
                    + " p.order_id=q.order_id AND p.sequence_no<q.sequence_no AND"
                    + " p.status<>'DELIVERED') ORDER BY next_attempt_time,id LIMIT 50")
    List<QmsReworkResultOutboxEntity> due();
}
