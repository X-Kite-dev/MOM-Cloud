package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesWarehouseFeedBirthEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 投料出生实际目录及完整原行读取；显式组织条件不能被软删除隐藏耗尽源。 */
@Mapper
public interface MesWarehouseFeedBirthMapper extends MomBaseMapper<MesWarehouseFeedBirthEntity> {
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_feed_birth_ready_260()")
    Boolean ready();

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT CASE WHEN birth_version IS NULL THEN 'HISTORICAL_UNPROVEN' ELSE 'BIRTH_REQUIRED' END
            FROM "mom_mes".mes_warehouse_feed
            WHERE id=#{feedId} AND tenant_id=#{tenant} AND factory_code=#{factory}
            """)
    String sourceState(@Param("feedId") Long feedId, @Param("tenant") Long tenant,
                       @Param("factory") String factory);

    /** 父工单和派工已经锁定；这里保留DB完整原字符串，包括微秒和所有原属性。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT (to_jsonb(f)-ARRAY['used_num','is_lock','update_by','update_time',
                'birth_version','birth_created_xid'])::text AS "feedSnapshot",
                t.bom_snapshot AS "bomSnapshot",to_jsonb(a)::text AS "arrangeSnapshot",
                a.actual_worksite_code AS "receivingWorksiteCode"
            FROM "mom_mes".mes_warehouse_feed f
            JOIN "mom_mes".mes_task t ON t.id=#{taskId} AND t.tenant_id=f.tenant_id
                AND t.factory_code=f.factory_code AND t.code=f.task_code
            JOIN "mom_mes".mes_task_arrange a ON a.id=#{arrangeId} AND a.tenant_id=f.tenant_id
                AND a.factory_code=f.factory_code AND a.task_code=f.task_code
                AND a.code=f.arrange_code AND a.produce_task_code=f.produce_task_code
            WHERE f.id=#{feedId} AND f.tenant_id=#{tenant} AND f.factory_code=#{factory}
                AND f.birth_version='MES_FEED_BIRTH_V1' AND f.birth_created_xid=txid_current()
            """)
    Map<String, Object> facts(@Param("feedId") Long feedId, @Param("taskId") Long taskId,
                            @Param("arrangeId") Long arrangeId, @Param("tenant") Long tenant,
                            @Param("factory") String factory);

    /** 一次读取本次真实feeding全集；历史无关联流水不被猜测认领。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT f.id::text AS "outflowId",m.stock_id::text AS "stockId",
                m.proof_key AS "mutationKey",f.operate_num::text AS "quantity",
                f.mat_unit AS "unit",m.before_snapshot::text AS "sourceSnapshot",
                m.after_snapshot::text AS "afterSnapshot",to_jsonb(f)::text AS "flowSnapshot"
            FROM "mom_mes".mes_warehouse_inout f
            JOIN "mom_mes".mes_feed_stock_mutation m ON m.proof_key=f.stock_out_mutation_key
                AND m.tenant_id=f.tenant_id AND m.factory_code=f.factory_code
                AND m.created_xid=f.stock_out_created_xid
            WHERE f.bill_id=#{feedId} AND f.tenant_id=#{tenant} AND f.factory_code=#{factory}
                AND f.biz_type='feeding' AND f.operate_type='out'
                AND f.stock_out_created_xid=txid_current()
            ORDER BY f.id
            """)
    List<Map<String, Object>> sources(@Param("feedId") Long feedId, @Param("tenant") Long tenant,
                                    @Param("factory") String factory);
}
