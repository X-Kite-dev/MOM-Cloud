package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesProductionFeedBatchEntity;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 独立总投料永久账及服务器首次报告事实；不向客户端开放写技术证据。 */
@Mapper
public interface MesProductionFeedBatchMapper extends MomBaseMapper<MesProductionFeedBatchEntity> {
    /** 只检查实际完整目录，不将缺表当成没有耗用。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_feed_batch_ready_262()")
    Boolean ready();

    /** 同一租户原号永不因换厂或协议换号。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    Boolean tryRequest(@Param("key") String key);

    /** 永久原号按租户读取，再由服务核真实工厂和本人，不能切厂误判ABSENT。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM \"mom_mes\".mes_production_feed_batch WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesProductionFeedBatchEntity permanentRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    /** 仅服务器本事务真实报告INSERT证据；原数量/单位不由请求声明。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT id,source_id,output_kind,tenant_id,factory_code,facts::text AS source_snapshot, "
            + "CASE WHEN output_kind='GOOD' THEN facts->>'report_num' ELSE facts->>'unqualified_num' END AS quantity, "
            + "facts->>'effective_report_num' AS effective_quantity,facts->>'corrected_report_num' AS corrected_quantity, "
            + "created_xid=txid_current() AS same_transaction FROM \"mom_mes\".mes_production_output_birth "
            + "WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND output_kind=#{kind} AND source_id=#{sourceId}")
    SourceBirth actualBirth(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                           @Param("kind") String kind, @Param("sourceId") Long sourceId);

    /** 真实投料首次出生必须完整；旧无凭证不能按批次补造。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_assert_feed_birth_complete_260(#{feedId})")
    void assertFeedBirth(@Param("feedId") Long feedId);

    /** 首次报告的内部技术事实，只读、不作为业务持久实体或CRUD。 */
    @Data
    class SourceBirth {
        /** 数据库实际出生证据主键。 */
        private Long id;
        /** 分种类的真实报告主键。 */
        private Long sourceId;
        /** GOOD/NG真实来源域。 */
        private String outputKind;
        /** 实际租户。 */
        private Long tenantId;
        /** 实际工厂。 */
        private String factoryCode;
        /** 首次完整数据库事实字符串。 */
        private String sourceSnapshot;
        /** PG原生numeric转文本，不能经过JSON浮点节点再转换。 */
        private String quantity;
        /** 原GOOD有效数量文本，NG不伪造该字段。 */
        private String effectiveQuantity;
        /** 原GOOD已纠正数量文本，NG不伪造该字段。 */
        private String correctedQuantity;
        /** 是否本事务首次产生，不能补旧报告。 */
        private Boolean sameTransaction;
    }
}
