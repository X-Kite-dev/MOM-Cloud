package com.kitex.mom.plan.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.plan.entity.PlnFormalPlanContractEntity;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.entity.PlnQualitySupplementDemandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PlnQualitySupplementDemandMapper extends MomBaseMapper<PlnQualitySupplementDemandEntity> {
    @Select("SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(CONCAT('PLN_QUALITY_SUPPLEMENT:', #{key}), 0))")
    Integer lockKey(@Param("key") String key);

    /**
     * 计量证明不能因人员可见范围或软删除而漏掉同号历史来源；不将结果作为业务列表返回。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT id, tenant_id, factory_code, demand_no, product_code, product_unit
            FROM pln_product_demand
            WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode} AND demand_no = #{demandNo}
            ORDER BY id LIMIT 2
            """)
    List<PlnProductDemandEntity> sourceDemands(@Param("tenantId") Long tenantId,
                                               @Param("factoryCode") String factoryCode,
                                               @Param("demandNo") String demandNo);

    /**
     * 原执行合同可能已被替代；只读原产品的全部核验单位，不借用根产品或当前版本作猜测。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("""
            SELECT id, unit_code
            FROM pln_formal_plan_contract
            WHERE tenant_id = #{tenantId} AND factory_code = #{factoryCode}
              AND source_demand_no = #{demandNo} AND product_code = #{productCode} AND supply_type = 'MAKE'
              AND verification_status = 'VERIFIED' AND contract_status IN ('PUBLISHED', 'SUPERSEDED')
            ORDER BY id
            """)
    List<PlnFormalPlanContractEntity> sourceProductMeasures(@Param("tenantId") Long tenantId,
                                                            @Param("factoryCode") String factoryCode,
                                                            @Param("demandNo") String demandNo,
                                                            @Param("productCode") String productCode);

    /**
     * 完整缺口占额不受人员可见范围影响；限定实际租户、工厂和原计划。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM pln_quality_supplement_demand WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND source_issue_id=#{issueId} AND del_flag='0' ORDER BY id")
    List<PlnQualitySupplementDemandEntity> sourceSupplements(@Param("tenantId") Long tenantId,
                                                             @Param("factoryCode") String factoryCode, @Param("issueId") Long issueId);

    /**
     * 归属核验读取完整根计划，不以首桶或人员可见子集代替整笔补产。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM pln_issue WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND source_demand_no=#{demandNo} AND source_mrp_id IS NULL ORDER BY id")
    List<com.kitex.mom.plan.entity.PlnIssueEntity> demandRoots(@Param("tenantId") Long tenantId,
                                                               @Param("factoryCode") String factoryCode, @Param("demandNo") String demandNo);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM plan_mat_mrp WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND plan_demand_no=#{demandNo} AND del_flag='0' ORDER BY id")
    List<com.kitex.mom.plan.entity.PlanMatMrpEntity> demandMrp(@Param("tenantId") Long tenantId,
                                                               @Param("factoryCode") String factoryCode, @Param("demandNo") String demandNo);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT * FROM pln_mps_line WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND demand_id=#{demandId} AND del_flag='0' AND (active=true OR pending=true) ORDER BY id")
    List<com.kitex.mom.plan.entity.PlnMpsLineEntity> liveMps(@Param("tenantId") Long tenantId,
                                                             @Param("factoryCode") String factoryCode, @Param("demandId") Long demandId);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select({"<script>SELECT * FROM pln_quality_supplement_demand WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND del_flag='0' AND source_issue_id IN",
            "<foreach collection='issueIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id</script>"})
    List<PlnQualitySupplementDemandEntity> sourceSupplementsForIssues(@Param("tenantId") Long tenantId,
                                                                      @Param("factoryCode") String factoryCode, @Param("issueIds") java.util.Collection<Long> issueIds);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select({"<script>SELECT * FROM pln_issue WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND source_mrp_id IS NULL AND source_demand_no IN",
            "<foreach collection='demandNos' item='value' open='(' separator=',' close=')'>#{value}</foreach> ORDER BY id</script>"})
    List<com.kitex.mom.plan.entity.PlnIssueEntity> rootsForDemands(@Param("tenantId") Long tenantId,
                                                                   @Param("factoryCode") String factoryCode, @Param("demandNos") java.util.Collection<String> demandNos);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select({"<script>SELECT * FROM plan_mat_mrp WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND del_flag='0' AND plan_demand_no IN",
            "<foreach collection='demandNos' item='value' open='(' separator=',' close=')'>#{value}</foreach> ORDER BY id</script>"})
    List<com.kitex.mom.plan.entity.PlanMatMrpEntity> mrpForDemands(@Param("tenantId") Long tenantId,
                                                                   @Param("factoryCode") String factoryCode, @Param("demandNos") java.util.Collection<String> demandNos);

    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select({"<script>SELECT * FROM pln_mps_line WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} AND del_flag='0' AND (active=true OR pending=true) AND demand_id IN",
            "<foreach collection='demandIds' item='value' open='(' separator=',' close=')'>#{value}</foreach> ORDER BY id</script>"})
    List<com.kitex.mom.plan.entity.PlnMpsLineEntity> mpsForDemands(@Param("tenantId") Long tenantId,
                                                                   @Param("factoryCode") String factoryCode, @Param("demandIds") java.util.Collection<Long> demandIds);
}
