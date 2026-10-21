package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesQmsInspectionCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** MES到QMS质检任务的永久原号、发送边界和明确回执。 */
@Mapper
public interface MesQmsInspectionCommandMapper extends MomBaseMapper<MesQmsInspectionCommandEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_qms_inspection_command_ready_v1()")
    Boolean installed();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(CONCAT('MES_QMS_INSPECTION_V2:',#{tenant},':',#{request}),0))")
    Boolean tryRequest(@Param("tenant") Long tenant, @Param("request") String request);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_qms_inspection_command WHERE tenant_id=#{tenant} AND request_id=#{request}")
    MesQmsInspectionCommandEntity original(@Param("tenant") Long tenant, @Param("request") String request);

    /** 每次真实调用前先独立提交；重试仍使用同一原号和原文。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("UPDATE \"mom_mes\".mes_qms_inspection_command SET status='OUTBOUND_STARTED',"
            + "attempt_count=attempt_count+1,last_attempt_time=now(),last_error=NULL,update_time=now() "
            + "WHERE id=#{id} AND tenant_id=#{tenant} AND command_fingerprint=#{fingerprint} "
            + "AND status IN ('REGISTERED','OUTBOUND_STARTED')")
    int beginAttempt(@Param("id") Long id, @Param("tenant") Long tenant, @Param("fingerprint") String fingerprint);

    /** 仅QMS明确返回正主键后固化一次；相同回放不改原任务。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("UPDATE \"mom_mes\".mes_qms_inspection_command SET status='QMS_CONFIRMED',qms_task_id=#{qmsTaskId},"
            + "last_error=NULL,update_time=now() WHERE id=#{id} AND tenant_id=#{tenant} "
            + "AND command_fingerprint=#{fingerprint} AND status='OUTBOUND_STARTED' AND qms_task_id IS NULL")
    int confirm(@Param("id") Long id, @Param("tenant") Long tenant,
                @Param("fingerprint") String fingerprint, @Param("qmsTaskId") Long qmsTaskId);

    /** 异常只作诊断；OUTBOUND_STARTED保持未知，不自动改回未发送。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Update("UPDATE \"mom_mes\".mes_qms_inspection_command SET last_error=#{message},update_time=now() "
            + "WHERE id=#{id} AND tenant_id=#{tenant} AND command_fingerprint=#{fingerprint} "
            + "AND status='OUTBOUND_STARTED'")
    int recordFailure(@Param("id") Long id, @Param("tenant") Long tenant,
                      @Param("fingerprint") String fingerprint, @Param("message") String message);
}
