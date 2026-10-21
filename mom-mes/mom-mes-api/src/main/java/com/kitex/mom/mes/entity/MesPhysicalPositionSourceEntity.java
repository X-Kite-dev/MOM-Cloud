package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 位置观察的每条精确原库存；不合并来源或制造库存。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_position_source")
@Schema(description = "位置观察的每条精确原库存；不合并来源或制造库存")
public class MesPhysicalPositionSourceEntity extends BaseEntity<MesPhysicalPositionSourceEntity> {
    /**
     * 实际工厂，须与原主账一致。
     */
    private String factoryCode;
    /**
     * 永久位置观察主账ID。
     */
    private Long surveyId;
    /**
     * 当时实际精确库存ID。
     */
    private Long stockId;
    /**
     * 该库存当时真实原报工ID。
     */
    private Long sourceReportId;
    /**
     * 原完整MES_PHYSICAL_SOURCE_V2版本。
     */
    private String sourceVersion;
    /**
     * 该来源完整原字符串，含旧V1、原工程及当时阻断。
     */
    private String sourceSnapshot;
    /**
     * 该原来源V2完整SHA。
     */
    private String sourceFingerprint;
    /**
     * 原库存native量，精确28/8，不等同母件对象数。
     */
    private BigDecimal nativeQuantity;
    /**
     * 原冻结库存单位，不从当前主数据猜单位。
     */
    private String nativeUnit;
    /**
     * 原工程换算的完整SN当量，不作PASS授权。
     */
    private BigDecimal equivalentQuantity;
}
