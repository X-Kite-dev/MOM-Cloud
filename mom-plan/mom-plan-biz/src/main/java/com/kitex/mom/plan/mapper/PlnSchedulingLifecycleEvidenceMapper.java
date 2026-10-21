package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlnSchedulingLifecycleEvidenceMapper {
    /**
     * 仅用于已授权精确任务的结构指纹与异常数量，不能按未验证租户枚举明细。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT 'details' AS section, count(*) AS total, count(*) FILTER (WHERE COALESCE(t.lock_status,false)) AS locked,
             count(*) FILTER (WHERE t.tenant_id IS DISTINCT FROM #{tenant} OR t.factory_code IS DISTINCT FROM #{factory} OR t.del_flag<>'0') AS invalid,
             md5(COALESCE(string_agg(md5(to_jsonb(t)::text),',' ORDER BY t.id),'')) AS digest FROM pln_scheduling_task_detail t WHERE t.task_id=#{taskId} AND t.del_flag='0'
            UNION ALL
            SELECT 'operations' AS section, count(*) AS total, count(*) FILTER (WHERE COALESCE(t.lock_status,false)) AS locked,
             count(*) FILTER (WHERE t.tenant_id IS DISTINCT FROM #{tenant} OR t.factory_code IS DISTINCT FROM #{factory} OR t.del_flag<>'0') AS invalid,
             md5(COALESCE(string_agg(md5(to_jsonb(t)::text),',' ORDER BY t.id),'')) AS digest FROM pln_scheduling_operation t WHERE t.task_id=#{taskId} AND t.del_flag='0'
            UNION ALL
            SELECT 'batches' AS section, count(*) AS total, count(*) FILTER (WHERE COALESCE(t.lock_status,false)) AS locked,
             count(*) FILTER (WHERE t.tenant_id IS DISTINCT FROM #{tenant} OR t.factory_code IS DISTINCT FROM #{factory} OR t.del_flag<>'0') AS invalid,
             md5(COALESCE(string_agg(md5(to_jsonb(t)::text),',' ORDER BY t.id),'')) AS digest FROM pln_scheduling_batch t WHERE t.task_id=#{taskId} AND t.del_flag='0'
            UNION ALL
            SELECT 'allocations' AS section, count(*) AS total, count(*) FILTER (WHERE false) AS locked,
             count(*) FILTER (WHERE t.tenant_id IS DISTINCT FROM #{tenant} OR t.factory_code IS DISTINCT FROM #{factory} OR t.del_flag<>'0') AS invalid,
             md5(COALESCE(string_agg(md5(to_jsonb(t)::text),',' ORDER BY t.id),'')) AS digest FROM pln_scheduling_batch_allocation t WHERE t.task_id=#{taskId} AND t.del_flag='0'
            UNION ALL
            SELECT 'sourceLocks' AS section, count(*) AS total, count(*) FILTER (WHERE true) AS locked,
             count(*) FILTER (WHERE t.tenant_id IS DISTINCT FROM #{tenant} OR t.factory_code IS DISTINCT FROM #{factory} OR t.del_flag<>'0') AS invalid,
             md5(COALESCE(string_agg(md5(to_jsonb(t)::text),',' ORDER BY t.id),'')) AS digest FROM pln_issue t WHERE t.lock_task_id=#{taskId}
            ORDER BY section
            """)
    List<Map<String, Object>> evidence(@Param("taskId") Long taskId, @Param("tenant") Long tenant, @Param("factory") String factory);
}
