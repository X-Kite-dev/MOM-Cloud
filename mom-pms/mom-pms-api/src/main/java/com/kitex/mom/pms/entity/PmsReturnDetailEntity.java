package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购退货
 *
 * @author kitex
 * @date 2025-02-24 08:38:40
 */
@Data
@TableName("pms_return_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "pms_return_detail")
public class PmsReturnDetailEntity extends BaseEntity<PmsReturnDetailEntity> {
    /**
     * 采购退货id
     */
    @Schema(description = "采购退货id")
    private Long returnId;
    /**
     * 采购订单明细id
     */
    @Schema(description = "采购订单明细id")
    private Long orderDetailId;
    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String planNo;
    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;
    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;
    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;
    /**
     * 退货数量
     */
    @Schema(description = "退货数量")
    private Double returnNum;

    @Schema(description = "WMS实时库存行ID")
    private Long liveStockId;
    @Schema(description = "原采购收料单ID快照")
    private Long sourceReceiptId;
    @Schema(description = "原采购收料批次明细ID快照")
    private Long sourceReceiptDetailId;
    @Schema(description = "仓库编码")
    private String warehouseCode;
    @Schema(description = "仓库名称")
    private String warehouseName;
    @Schema(description = "库位编码")
    private String siteCode;
    @Schema(description = "库位名称")
    private String siteName;
    @Schema(description = "容器编码")
    private String containerCode;
    @Schema(description = "物料批次")
    private String matBatch;
    @Schema(description = "质量状态快照")
    private String qualityStatus;
    @Schema(description = "质量结果快照")
    private String qualityResult;
    @Schema(description = "有效期快照")
    private LocalDate validEndDate;
    @Schema(description = "首次入库时间快照")
    private LocalDateTime stockInTime;
    @Schema(description = "申请时可退数量")
    private BigDecimal returnableNumSnapshot;
    @Schema(description = "WMS实际退供数量")
    private BigDecimal actualReturnNum;
    @Schema(description = "库存事实指纹")
    private String stockFingerprint;
    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
