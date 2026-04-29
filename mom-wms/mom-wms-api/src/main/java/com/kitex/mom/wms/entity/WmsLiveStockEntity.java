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

/**
 * 实时库存
 *
 * @author kitex
 * @date 2024-12-18 17:36:37
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_live_stock")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "实时库存")
public class WmsLiveStockEntity extends BaseEntity<WmsLiveStockEntity> {

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 包装规格
     */
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 供应商
     */
    @Schema(description = "供应商")
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
     * 库存名称
     */
    @Schema(description = "库存名称")
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
     * 数量
     */
    @Schema(description = "数量")
    private Double matNum;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

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

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 状态 是否被锁定;0否 1是
     */
    @Schema(description = "状态 是否被锁定;0否 1是")
    private Integer status;

    /**
     * 扩展字段
     */
    @Schema(description = "扩展字段")
    private String expansion;

    /**
     * 有效期检测状态
     */
    @Schema(description = "有效期检测状态")
    private Boolean expirationStatus;

    /**
     * 呆滞物料检测状态
     */
    @Schema(description = "呆滞物料检测状态")
    private Boolean slowMaterialStatus;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 出库通知单明细id
     */
    @TableField(exist = false)
    @Schema(description = "出库通知单明细id")
    private Long noticeDetailId;
}