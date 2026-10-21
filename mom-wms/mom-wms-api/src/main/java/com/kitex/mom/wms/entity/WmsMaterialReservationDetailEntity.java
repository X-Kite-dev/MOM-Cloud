package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TenantTable
@TableName("wms_material_reservation_detail")
@EqualsAndHashCode(callSuper = true)
public class WmsMaterialReservationDetailEntity extends BaseEntity<WmsMaterialReservationDetailEntity> {
    private Long reservationId;
    private String reservationNo;
    private Long liveStockId;
    private String requirementMatCode;
    private String actualMatCode;
    private String actualMatName;
    private String actualMatUnit;
    private BigDecimal substituteRatio;
    private BigDecimal requirementNum;
    private BigDecimal reservedNum;
    private BigDecimal consumedNum;
    /**
     * 已消耗后由生产退料回补的数量，仅用于净消耗审计。
     */
    private BigDecimal returnedNum;
    private BigDecimal releasedNum;
    /**
     * ORIGINAL / RETURN_REPLENISH。
     */
    private String detailType;
    /**
     * 退料回补明细所对应的上一代消费明细。
     */
    private Long parentDetailId;
    /**
     * 触发本次回补的退料入库单明细，用于防止重复回补。
     */
    private Long returnBillDetailId;
    /**
     * 受控重配审计记录；原始预留和退料回补为空。
     */
    private Long adjustmentId;
    private String warehouseCode;
    private String warehouseName;
    private String siteCode;
    private String siteName;
    private String containerCode;
    private String matBatch;
    private String supplierCode;
    private String qualityResult;
    private String complianceMode;
    private String complianceReferenceNo;
    private LocalDate complianceValidEnd;
    private String complianceSnapshot;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
