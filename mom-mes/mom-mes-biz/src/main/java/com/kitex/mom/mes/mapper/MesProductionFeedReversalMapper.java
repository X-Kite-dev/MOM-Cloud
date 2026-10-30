package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedBatchEntity;
import com.kitex.mom.mes.entity.MesProductionFeedReversalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 独立逐产出耗料纠正永久主账；原号先按租户取得，再核实际本人和工厂。
 */
@Mapper
public interface MesProductionFeedReversalMapper extends MomBaseMapper<MesProductionFeedReversalEntity> {
    /**
     * 实际目录不完整时禁止恢复，不能以空集合当无耗用。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_feed_restore_ready_v1()")
    Boolean ready();

    /**
     * 当前没有完整恢复业务目录时明确关闭；不会调用不存在的PG函数或据此判ABSENT。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT to_regprocedure('\"mom_mes\".mom_feed_restore_ready_v1()') IS NOT NULL")
    Boolean installed();

    /**
     * 同租户原号跨工厂/后续协议保持同一永久锁域。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean tryRequest(@Param("key") String key);

    /**
     * 永久原号查询不先按工厂隐藏，服务必须再核真实本人及工厂。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_reversal "
            + "WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesProductionFeedReversalEntity permanentRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    /**
     * 独立已提交原batch，原字段及产出不可用当前工程重建。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_batch "
            + "WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND id=#{batchId} AND created_xid<>txid_current()")
    MesProductionFeedBatchEntity originalBatch(@Param("tenantId") Long tenantId,
                                               @Param("factoryCode") String factoryCode, @Param("batchId") Long batchId);

    /**
     * 只核不可变原全集、出生与原正向凭证，不要求当前余额等于首次耗用后的值。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_assert_feed_batch_original_v1(#{batchId})")
    void assertOriginalBatch(@Param("batchId") Long batchId);

    /**
     * 完整选定集合一次核本地原件，复用实际工序图；通过不代表QMS/WMS永久冻结许可。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_assert_feed_restore_local_origins_v1("
            + "ARRAY(SELECT value::bigint FROM jsonb_array_elements_text(CAST(#{outputIdsJson} AS jsonb))))")
    void assertLocalOrigins(@Param("outputIdsJson") String outputIdsJson);

    /**
     * 数据库按typed真实来源再次核占用/质量/在途/包装及已知下游，不传客户端事实。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_assert_feed_restore_output_clear_v1(#{outputId})")
    void assertOutputClear(@Param("outputId") Long outputId);
}
