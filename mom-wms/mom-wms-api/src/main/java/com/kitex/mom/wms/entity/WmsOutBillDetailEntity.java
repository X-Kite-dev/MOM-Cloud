package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * 出库单明细
 *
 * @author kitex
 * @date 2024-12-24 11:38:35
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_out_bill_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "出库单明细")
public class WmsOutBillDetailEntity extends BaseEntity<WmsOutBillDetailEntity> {

    /** 受控出库锁定的实时库存行；采购退供等场景禁止改用其他库存。 */
    @Schema(description = "受控出库锁定的实时库存行ID")
    private Long sourceStockId;

    /**
     * 单据id
     */
    @Schema(description = "单据id")
    private Long billId;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "BOM需求物料编码")
    private String requirementMatCode;

    @Schema(description = "BOM需求物料名称")
    private String requirementMatName;

    @Schema(description = "BOM需求数量当量")
    private BigDecimal requirementMatNum;

    @Schema(description = "是否为替代料")
    private Boolean substituteFlag;

    @Schema(description = "替代比例")
    private BigDecimal substituteRatio;

    @Schema(description = "WMS物料预留单号")
    private String reservationNo;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料分组
     */
    @Schema(description = "物料分组")
    private String matGroup;

    /**
     * 物料分组名称
     */
    @Schema(description = "物料分组名称")
    private String matGroupName;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 包装规格
     */
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 包装单位
     */
    @Schema(description = "包装单位")
    private String operateUnit;

    /**
     * 操作数量（包装数量）
     */
    @Schema(description = "操作数量（包装数量）")
    private Double operateNum;

    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 物料数量
     */
    @Schema(description = "物料数量")
    private Double matNum;

    /**
     * 当前库存数量
     */
    @TableField(exist = false)
    @Schema(description = "当前库存数量")
    private Double maxOutNum;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 入库时间
     */
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期
     */
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截至日期
     */
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

//	/**
//	* 实时库存id
//	*/
//    @Schema(description="实时库存id")
//    private Long liveId;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshop;

    /**
     * 关联的入库通知单明细id
     */
    @Schema(description = "关联的入库通知单明细id")
    private Long noticeDetailId;

    /**
     * 扩展字段
     */
    @Schema(description = "扩展字段")
    private String expansion;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 状态
     */
    @TableField(exist = false)
    @Schema(description = "状态")
    private String state;

    /**
     * 单据类型
     */
    @TableField(exist = false)
    @Schema(description = "单据类型")
    private String billType;

    /**
     * 出库单号
     */
    @TableField(exist = false)
    @Schema(description = "出库单号")
    private String billNo;

    /**
     * 通知单号
     */
    @TableField(exist = false)
    @Schema(description = "通知单号")
    private String noticeId;
}
