package com.kitex.mom.mes.entity;

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

/**
 * <p>
 * 线边库出入库流水 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-02-20 17:26:14
 */
@Data
@TenantTable
@TableName("mes_warehouse_inout")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "线边库出入库流水")
public class MesWarehouseInoutEntity extends BaseEntity<MesWarehouseInoutEntity> {

    /**
     * 单据id
     */
    @Schema(description = "单据id")
    private Long billId;

    /**
     * 单据号
     */
    @Schema(description = "单据号")
    private String billNo;

    /**
     * 工序任务编号
     */
    @Schema(description = "工序任务编号")
    private String produceTaskCode;

    /**
     * 来源单号
     */
    @Schema(description = "来源单号")
    private String resourceNo;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matCode;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 批次号
     */
    @Schema(description = "批次号")
    private String matBatch;

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
     * 有效截止日期
     */
    @Schema(description = "有效截止日期")
    private LocalDate matValidEndDate;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料条码图片
     */
    @Schema(description = "物料条码图片")
    private String matBarcodeImage;

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
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 容器名称
     */
    @Schema(description = "容器名称")
    private String containerName;

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
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal operateNum;

    /**
     * 操作类型
     */
    @Schema(description = "操作类型")
    private String operateType;

    /**
     * 业务类型
     */
    @Schema(description = "业务类型")
    private String bizType;

    /**
     * 质检状态
     */
    @Schema(description = "质检状态")
    private String inspectionStatus;

    /**
     * 物料明细类型 原材料、半成品、在制品
     */
    @Schema(description = "物料明细类型 原材料、半成品、在制品")
    private String matDetailType;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
