package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesTaskReportEntity;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/** 只读单位原件；先限真实组织与派工，再分组，未知单位和数量不默认为零。 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface MesWipRedirectBasisMapper {
    record UnitGroup(
            String taskCode,
            String operationCode,
            String matCode,
            String unit,
            BigDecimal quantity,
            long rowCount,
            long quantityCount) {}

    @Select(
            "SELECT"
                + " id,tenant_id,factory_code,del_flag,status,task_code,produce_task_code,arrange_code,mat_code,report_unit"
                + " FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND id=#{id} AND del_flag='0'")
    MesTaskReportEntity source(
            @Param("tenant") Long tenant, @Param("factory") String factory, @Param("id") Long id);

    @Select(
            "SELECT task_code,produce_task_code AS operation_code,mat_code,report_unit AS"
                + " unit,SUM(COALESCE(effective_report_num,report_num)) AS quantity,COUNT(*) AS"
                + " row_count,COUNT(COALESCE(effective_report_num,report_num)) AS quantity_count"
                + " FROM mom_mes.mes_task_report WHERE tenant_id=#{tenant} AND"
                + " factory_code=#{factory} AND arrange_code=#{arrange} AND status='EFFECTIVE' AND"
                + " del_flag='0' GROUP BY task_code,produce_task_code,mat_code,report_unit ORDER BY"
                + " task_code,produce_task_code,mat_code,report_unit LIMIT 3")
    List<UnitGroup> targetUnits(
            @Param("tenant") Long tenant,
            @Param("factory") String factory,
            @Param("arrange") String arrange);
}
