package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MRP跨需求库存Pegging台账。每一行把一个BOM需求路径稳定连接到一条WMS库存来源。
 */
@Data
@TenantTable
@TableName("plan_mrp_inventory_pegging")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MRP跨需求分时段库存Pegging台账")
public class PlanMrpInventoryPeggingEntity extends BaseEntity<PlanMrpInventoryPeggingEntity> {
    private Long mrpId;
    private String mrpRunNo;
    private String planDemandNo;
    private String saleOrderCode;
    private String warehouseCode;
    private String warehouseName;
    private String materialCode;
    private String materialName;
    private String materialUnit;
    private String bomPath;
    private Integer bomLevel;
    private LocalDate demandDate;
    private LocalDate timeBucket;
    private Integer demandPriority;
    private Integer allocationSequence;
    private String sourceType;
    private Long sourceStockId;
    private String sourceBatchNo;
    private String sourceSiteCode;
    private String sourceSiteName;
    private String sourceContainerCode;
    private String sourceSupplierCode;
    private String sourceQualityResult;
    private LocalDate sourceValidEndDate;
    private LocalDateTime sourceInTime;
    private BigDecimal peggedNum;
    private String status;
    private String reservationNo;
    private LocalDateTime reservationTime;
    private String evidenceFingerprint;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
