package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只查询已授权实际工厂；每个相关表均显式绑定租户/工厂，不拼接用户SQL。
 */
@Mapper
public interface MesTraceEvidenceMapper extends BaseMapper<MesTaskReportEntity> {
    @Options(timeout = 30)
    @Select("""
            <script>
            SELECT r.* FROM mes_task_report r
            WHERE r.del_flag='0' AND r.tenant_id=#{tenant} AND r.factory_code=#{factory}
            <choose>
              <when test="kind == 'TASK'">AND r.task_code=#{code}</when>
              <when test="kind == 'TRANSFER'">AND r.transfer_code=#{code}</when>
              <when test="kind == 'REPORT'">AND r.id=#{reportId}</when>
              <when test="kind == 'WRAP'">
                AND EXISTS(SELECT 1 FROM mes_wrap_record w WHERE w.report_id=r.id AND w.del_flag='0'
                  AND w.tenant_id=#{tenant} AND w.factory_code=#{factory} AND w.wrap_code=#{code})
              </when>
              <when test="kind == 'CARRIER'">AND r.carrier_code=#{code}</when>
              <when test="kind == 'MATERIAL'">
                AND (EXISTS(SELECT 1 FROM mes_report_material_consume c WHERE c.report_id=r.id AND c.del_flag='0'
                  AND c.tenant_id=#{tenant} AND c.factory_code=#{factory} AND c.mat_code=#{code} AND c.mat_batch=#{batch})
                OR EXISTS(SELECT 1 FROM mes_warehouse_inout i WHERE i.bill_id=r.id AND i.del_flag='0'
                  AND i.tenant_id=#{tenant} AND i.factory_code=#{factory} AND i.biz_type='back_flush_out'
                  AND i.mat_code=#{code} AND i.mat_batch=#{batch}))
              </when>
              <otherwise>AND 1=0</otherwise>
            </choose>
            ORDER BY r.id DESC
            </script>
            """)
    Page<MesTaskReportEntity> trace(Page<MesTaskReportEntity> page, @Param("tenant") Long tenant,
                                    @Param("factory") String factory, @Param("kind") String kind, @Param("code") String code,
                                    @Param("batch") String batch, @Param("reportId") Long reportId);
}
