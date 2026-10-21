package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsMesReceiptReconcileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface QmsMesReceiptReconcileMapper extends BaseMapper<QmsMesReceiptReconcileEntity> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lockKey(@Param("key") String key);

    // 仅后台发现待办，返回确切租户/工厂；工作器随后恢复单租户单工厂上下文及行锁。
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT d.* FROM qms_bad_info_detail d WHERE d.id>#{afterId} AND d.resource_type IN('MES_WIP_TRANSFER','MES_PRODUCTION_NG') AND d.mes_decision_key IS NOT NULL AND d.del_flag='0' AND d.status IN('WAIT_EXECUTION','WAIT_VERIFY','CLOSED','WAIT_MRB') AND NOT EXISTS(SELECT 1 FROM qms_mes_receipt_reconcile r WHERE r.detail_id=d.id AND r.decision_key=d.mes_decision_key) ORDER BY d.id LIMIT #{limit}")
    List<QmsBadInfoDetailEntity> undiscovered(@Param("limit") int limit, @Param("afterId") long afterId);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT after_id FROM qms_mes_receipt_discovery_cursor WHERE name='MES_CASE_RECEIPT_V1' FOR UPDATE")
    Long lockDiscoveryCursor();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("UPDATE qms_mes_receipt_discovery_cursor SET after_id=#{afterId},last_scan_time=now() WHERE name='MES_CASE_RECEIPT_V1'")
    int advanceDiscovery(@Param("afterId") long afterId);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM qms_mes_receipt_reconcile WHERE del_flag='0' AND status IN('PENDING','WAIT_EXECUTION','WAIT_VERIFY','RETRY_WAIT') AND next_check_time<=now() ORDER BY next_check_time,id LIMIT #{limit}")
    List<QmsMesReceiptReconcileEntity> due(@Param("limit") int limit);
}
