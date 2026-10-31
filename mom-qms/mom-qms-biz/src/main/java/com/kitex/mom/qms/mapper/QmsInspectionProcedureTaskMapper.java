package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.qms.dto.QmsInspectionSamples;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface QmsInspectionProcedureTaskMapper
        extends BaseMapper<QmsInspectionProcedureTaskEntity> {
    @Select(
            "SELECT * FROM mom_qms.qms_inspection_procedure_task WHERE id=#{id} AND"
                + " tenant_id=#{tenant} AND factory_code=#{factory} AND del_flag='0' FOR UPDATE")
    QmsInspectionProcedureTaskEntity lockForInspection(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);

    @Select(
            "SELECT r.detail_id,COUNT(*) AS total,SUM(CASE WHEN r.qualified=FALSE THEN 1 ELSE 0"
                + " END) AS unqualified,SUM(CASE WHEN r.qualified IS NULL THEN 1 ELSE 0 END) AS"
                + " undetermined FROM mom_qms.qms_inspection_procedure_item_result_record r JOIN"
                + " mom_qms.qms_inspection_procedure_task t ON t.id=r.task_id WHERE t.id=#{id} AND"
                + " t.tenant_id=#{tenant} AND t.factory_code=#{factory} AND t.del_flag='0' GROUP BY"
                + " r.detail_id ORDER BY r.detail_id LIMIT 5001")
    @ConstructorArgs({
        @Arg(column = "detail_id", javaType = Long.class),
        @Arg(column = "total", javaType = long.class),
        @Arg(column = "unqualified", javaType = long.class),
        @Arg(column = "undetermined", javaType = long.class)
    })
    List<QmsInspectionSamples> inspectionSamples(
            @Param("id") Long id, @Param("tenant") Long tenant, @Param("factory") String factory);
}
