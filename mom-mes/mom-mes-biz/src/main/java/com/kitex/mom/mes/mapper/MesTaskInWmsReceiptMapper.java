package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesTaskInWmsReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MesTaskInWmsReceiptMapper extends MomBaseMapper<MesTaskInWmsReceiptEntity> {
    /** 先按真实租户与原号取永久回执，服务再核厂；切厂或逻辑删除不能解释为未受理。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_task_in_wms_receipt WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesTaskInWmsReceiptEntity permanentRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CONCAT('MES_TASK_IN_WMS:', #{requestId}), 0))")
    void lockRequest(@Param("requestId") String requestId);
}
