package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.dto.MesReworkIdentifierCandidateDTO;
import com.kitex.mom.mes.entity.MesReworkIdentifierUsageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 返工原动态实例使用账持久层。
 *
 * <p>候选查询同时绑定原工单、原实际工序、有效GOOD报告和未撤回正向节点操作，
 * 不能只凭今天仍能搜索到的外码或内部SN认领历史实物。</p>
 */
@Mapper
public interface MesReworkIdentifierUsageMapper extends BaseMapper<MesReworkIdentifierUsageEntity> {

    @Select("""
            SELECT b.id AS binding_id,
                   b.binding_group_id,
                   b.task_id AS source_task_id,
                   b.task_code AS source_task_code,
                   r.produce_task_code AS source_produce_task_code,
                   a.source_route_node_code AS route_node_code,
                   g.binding_group_code,
                   b.external_namespace,
                   b.external_code,
                   b.canonical_identifier,
                   b.status AS binding_status,
                   b.version_no AS binding_version,
                   CASE WHEN b.status='ACTIVE' THEN 'ACTIVE_EXTERNAL' ELSE 'CANONICAL_ONLY' END AS input_mode,
                   o.id AS source_operation_id,
                   r.id AS source_report_id,
                   g.definition_fingerprint,
                   g.graph_fingerprint,
                   g.source_route_fingerprint,
                   count(*) OVER (PARTITION BY b.id) AS source_operation_count
              FROM "mom_mes".mes_identifier_binding b
              JOIN "mom_mes".mes_identifier_binding_group g
                ON g.tenant_id=b.tenant_id AND g.factory_code=b.factory_code
               AND g.id=b.binding_group_id AND g.task_id=b.task_id AND g.task_code=b.task_code
               AND g.status='FROZEN' AND g.del_flag='0'
              JOIN "mom_mes".mes_identifier_binding_operation o
                ON o.tenant_id=b.tenant_id AND o.factory_code=b.factory_code
               AND o.binding_id=b.id AND o.binding_group_id=b.binding_group_id
               AND o.action IN ('BIND','USE','RELEASE') AND o.del_flag='0'
              JOIN "mom_mes".mes_task_report r
                ON r.tenant_id=o.tenant_id AND r.factory_code=o.factory_code
               AND r.id=o.report_id AND r.arrange_code=o.arrange_code AND r.route_code=o.route_code
               AND r.status='EFFECTIVE' AND r.del_flag='0'
              JOIN "mom_mes".mes_task_arrange a
                ON a.tenant_id=r.tenant_id AND a.factory_code=r.factory_code
               AND a.code=r.arrange_code AND a.task_code=r.task_code
               AND a.produce_task_code=r.produce_task_code AND a.del_flag='0'
             WHERE b.tenant_id=#{tenantId} AND b.factory_code=#{factoryCode}
               AND b.task_id=#{sourceTaskId} AND b.task_code=#{sourceTaskCode}
               AND r.task_code=#{sourceTaskCode}
               AND r.produce_task_code=#{sourceProduceTaskCode}
               AND b.status IN ('ACTIVE','RELEASED') AND b.del_flag='0'
               AND NOT EXISTS (
                    SELECT 1 FROM "mom_mes".mes_identifier_binding_operation inverse
                     WHERE inverse.tenant_id=o.tenant_id AND inverse.factory_code=o.factory_code
                       AND inverse.original_operation_id=o.id AND inverse.del_flag='0')
             ORDER BY b.id,o.id
             LIMIT 1001
            """)
    List<MesReworkIdentifierCandidateDTO> selectCandidates(@Param("tenantId") Long tenantId,
                                                            @Param("factoryCode") String factoryCode,
                                                            @Param("sourceTaskId") Long sourceTaskId,
                                                            @Param("sourceTaskCode") String sourceTaskCode,
                                                            @Param("sourceProduceTaskCode") String sourceProduceTaskCode);
}
