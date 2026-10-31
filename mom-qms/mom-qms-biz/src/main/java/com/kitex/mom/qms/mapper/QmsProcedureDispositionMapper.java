package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.entity.*;

import org.apache.ibatis.annotations.*;

@Mapper
public interface QmsProcedureDispositionMapper
        extends BaseMapper<QmsProcedureDispositionReceiptEntity> {
    @Select("SELECT mom_qms.mom_procedure_disposition_ready_v1()")
    Integer ready();

    @Select(
            "SELECT * FROM mom_qms.qms_procedure_disposition_receipt WHERE tenant_id=#{tenant} AND"
                    + " factory_code=#{factory} AND request_id=#{request}")
    QmsProcedureDispositionReceiptEntity receipt(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("request") String request);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0'")
    QmsInspectionProcedureTaskEntity parent(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' FOR UPDATE")
    QmsInspectionProcedureTaskEntity parentLocked(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT COUNT(*) FROM mom_qms.qms_bad_info WHERE resource_task_id=#{id} AND"
                + " resource_type='PROCEDURE' AND tenant_id=#{tenant} AND factory_code=#{factory}"
                + " AND del_flag='0'")
    int linkedCases(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT COUNT(*) FROM mom_qms.qms_inspection_revision_run WHERE task_id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory} AND phase NOT"
                    + " IN('DONE','STOPPED')")
    int activeRevision(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_handle_record WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionProcedureHandleRecordEntity handleRecord(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_history WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    QmsInspectionProcedureHistoryEntity history(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT * FROM mom_qms.qms_inspection_event WHERE id=#{id} AND tenant_id=#{tenant} AND"
                    + " factory_code=#{factory}")
    QmsInspectionEventEntity event(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT status FROM mom_qms.qms_inspection_event_outbox WHERE id=#{id} AND"
                    + " tenant_id=#{tenant} AND factory_code=#{factory}")
    String delivery(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_procedure_handle_record(id,tenant_id,factory_code,task_id,request_id,handle_num,handle_result,handle_remark,handle_person_id,handle_person_name,handle_time,status,create_by,create_time,del_flag)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{requestId},#{handleNum},#{handleResult},#{handleRemark},#{handlePersonId},#{handlePersonName},#{handleTime},#{status},#{createBy},#{handleTime},'0')")
    int insertRecord(QmsInspectionProcedureHandleRecordEntity e);

    @Insert(
            "INSERT INTO"
                + " mom_qms.qms_inspection_procedure_history(id,tenant_id,factory_code,task_id,type,num,time,qualified_status,detail_name,create_by,create_time,del_flag)"
                + " VALUES(#{id},#{tenantId},#{factoryCode},#{taskId},#{type},#{num},#{time},NULL,#{detailName},#{createBy},#{time},'0')")
    int insertHistory(QmsInspectionProcedureHistoryEntity e);

    @Update(
            "UPDATE mom_qms.qms_inspection_procedure_task SET"
                + " result=#{task.result},status=#{task.status},usable_num=#{task.usableNum},handle_num=#{task.handleNum},handle_result=#{task.handleResult},update_by=#{actor},update_time=CURRENT_TIMESTAMP"
                + " WHERE id=#{task.id} AND tenant_id=#{task.tenantId} AND"
                + " factory_code=#{task.factoryCode} AND del_flag='0' AND result='UN_QUALIFIED' AND"
                + " status='UNHANDLED' AND num=#{task.num} AND usable_num=#{usable} AND"
                + " handle_num=#{handled}")
    int updateTask(
            @Param("task") QmsInspectionProcedureTaskEntity task,
            @Param("usable") int usable,
            @Param("handled") int handled,
            @Param("actor") String actor);
}
