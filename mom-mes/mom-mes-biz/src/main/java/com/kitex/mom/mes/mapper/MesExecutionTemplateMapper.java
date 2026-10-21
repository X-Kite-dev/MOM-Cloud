package com.kitex.mom.mes.mapper;

import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 生产执行模板数据访问。
 */
@Mapper
public interface MesExecutionTemplateMapper extends MomBaseMapper<MesExecutionTemplateEntity> {

    /**
     * 同一模板编码的版本创建与发布必须串行。
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CONCAT('MES_EXECUTION_TEMPLATE:', #{factoryCode}, ':', #{templateCode}), 0))")
    Integer lockTemplate(@Param("factoryCode") String factoryCode, @Param("templateCode") String templateCode);

    /** 当前工程候选仅用于发布校验；执行仍只读原工单完整冻结图。 */
    @Select("SELECT mom_mes.mom_identifier_template_nodes_290(#{tenantId}, #{factoryCode}, #{processRouteCode})::text")
    String templateNodes(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode,
                         @Param("processRouteCode") String processRouteCode);
}
