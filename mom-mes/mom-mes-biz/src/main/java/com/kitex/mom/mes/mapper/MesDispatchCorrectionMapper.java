package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.*;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 所有精确查询绑定实名实际组织；锁后强制清SESSION缓存，防初次空结果复用。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesDispatchCorrectionMapper {

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_DISPATCH_CORRECTION:',#{tenant},':',#{factory},':',#{key}),0))")
    Integer lock(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("key") String key);

    @Select("SELECT * FROM mom_mes.mes_dispatch_correction_request WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesDispatchCorrectionRequestEntity request(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_dispatch_correction_request WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesDispatchCorrectionRequestEntity lockedRequest(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_dispatch_correction_operation WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND request_id=#{request}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesDispatchCorrectionOperationEntity original(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("request") String request);

    @Select("SELECT * FROM mom_mes.mes_dispatch_correction_operation WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND correction_id=#{id} ORDER BY created_time,id")
    List<MesDispatchCorrectionOperationEntity> operations(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT count(*) FROM mom_mes.mes_dispatch_correction_request WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_id=#{arrange} AND status='SUBMITTED'")
    long pending(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") Long arrange);

    @Select("<script>SELECT * FROM mom_mes.mes_dispatch_correction_request WHERE tenant_id=#{tenant} AND factory_code=#{factory} <if test='status != null'>AND status=#{status}</if><if test='arrange != null'> AND arrange_id=#{arrange}</if> ORDER BY submitted_time DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<MesDispatchCorrectionRequestEntity> page(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("status") String status, @Param("arrange") Long arrange, @Param("size") long size, @Param("offset") long offset);

    @Select("<script>SELECT count(*) FROM mom_mes.mes_dispatch_correction_request WHERE tenant_id=#{tenant} AND factory_code=#{factory} <if test='status != null'>AND status=#{status}</if><if test='arrange != null'> AND arrange_id=#{arrange}</if></script>")
    long count(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("status") String status, @Param("arrange") Long arrange);

    @Select("SELECT * FROM mom_mes.mes_produce_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task} AND code=#{code} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesProduceTaskEntity produce(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task, @Param("code") String code);

    @Select("SELECT * FROM mom_mes.mes_task WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND code=#{task} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesTaskEntity task(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task);

    @Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesTaskArrangeEntity arrange(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT * FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND correction_request_id=#{id} AND del_flag='0'")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MesTaskArrangeEntity replacement(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select("SELECT count(*) FROM mom_mes.mes_task_arrange WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND task_code=#{task} AND produce_task_code=#{produce} AND id!=#{exclude} AND status NOT IN ('CLOSE','FINISH') AND del_flag='0'")
    long otherActive(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("task") String task, @Param("produce") String produce, @Param("exclude") Long exclude);

    @Select("SELECT count(*) FROM mom_mes.mes_workpiece_time_session WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND arrange_id=#{arrange}")
    long workpieceFacts(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("arrange") Long arrange);

    @Select("SELECT * FROM mom_mes.mes_execution_template WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND status='PUBLISHED' AND del_flag='0' ORDER BY id")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<MesExecutionTemplateEntity> templates(@Param("tenant") Long tenant, @Param("factory") String factory);

    @Insert("INSERT INTO mom_mes.mes_dispatch_correction_request(id,tenant_id,factory_code,arrange_id,arrange_code,task_code,produce_task_code,status,source_fingerprint,evidence_fingerprint,target_shift_code,target_shift_name,target_worksite_codes,reason,applicant_id,applicant_code,applicant_name,submitted_time,before_snapshot,proposal_snapshot,original_policy_snapshot) VALUES(#{id},#{tenantId},#{factoryCode},#{arrangeId},#{arrangeCode},#{taskCode},#{produceTaskCode},#{status},#{sourceFingerprint},#{evidenceFingerprint},#{targetShiftCode},#{targetShiftName},#{targetWorksiteCodes},#{reason},#{applicantId},#{applicantCode},#{applicantName},#{submittedTime},#{beforeSnapshot},#{proposalSnapshot},#{originalPolicySnapshot})")
    int insertRequest(MesDispatchCorrectionRequestEntity row);

    @Update("UPDATE mom_mes.mes_dispatch_correction_request SET status=#{status},reviewer_id=#{reviewerId},reviewer_code=#{reviewerCode},reviewer_name=#{reviewerName},reviewed_time=#{reviewedTime},review_reason=#{reviewReason},replacement_arrange_id=#{replacementArrangeId},replacement_arrange_code=#{replacementArrangeCode},after_snapshot=#{afterSnapshot} WHERE id=#{id} AND tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND status='SUBMITTED'")
    int finish(MesDispatchCorrectionRequestEntity row);

    @Insert("INSERT INTO mom_mes.mes_dispatch_correction_operation(id,tenant_id,factory_code,correction_id,request_id,request_hash,action,actor_id,actor_code,reason,created_time,result_json) VALUES(#{id},#{tenantId},#{factoryCode},#{correctionId},#{requestId},#{requestHash},#{action},#{actorId},#{actorCode},#{reason},#{createdTime},#{resultJson})")
    int insertOperation(MesDispatchCorrectionOperationEntity row);
}
