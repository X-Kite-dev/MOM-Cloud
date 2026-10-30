package com.kitex.mom.pms.agent.mapper;

import com.kitex.mom.pms.agent.model.PmsProcurementRiskRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PMS 采购履约风险只读聚合查询。
 */
@Mapper
public interface PmsAgentProcurementRiskMapper {

    PmsProcurementRiskRow.Summary selectSummary(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("orderId") Long orderId,
            @Param("orderNo") String orderNo,
            @Param("observedAt") LocalDateTime observedAt,
            @Param("horizonEnd") LocalDateTime horizonEnd);

    List<PmsProcurementRiskRow.RiskLine> selectRiskLines(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("orderId") Long orderId,
            @Param("orderNo") String orderNo,
            @Param("observedAt") LocalDateTime observedAt,
            @Param("horizonEnd") LocalDateTime horizonEnd,
            @Param("limit") Integer limit);
}
