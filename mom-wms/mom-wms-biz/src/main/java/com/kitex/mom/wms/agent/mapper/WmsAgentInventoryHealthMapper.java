package com.kitex.mom.wms.agent.mapper;

import com.kitex.mom.wms.agent.model.WmsInventoryHealthRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * WMS 库存健康只读聚合查询。
 */
@Mapper
public interface WmsAgentInventoryHealthMapper {

    WmsInventoryHealthRow.Summary selectSummary(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("today") LocalDate today,
            @Param("nearExpiryEnd") LocalDate nearExpiryEnd,
            @Param("slowBefore") LocalDateTime slowBefore);

    List<WmsInventoryHealthRow.Unit> selectUnitSummaries(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("today") LocalDate today,
            @Param("nearExpiryEnd") LocalDate nearExpiryEnd,
            @Param("slowBefore") LocalDateTime slowBefore);

    List<WmsInventoryHealthRow.ExceptionStock> selectExceptions(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("today") LocalDate today,
            @Param("nearExpiryEnd") LocalDate nearExpiryEnd,
            @Param("slowBefore") LocalDateTime slowBefore,
            @Param("limit") Integer limit);
}
