package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 出入库流水
 *
 * @author kitex
 * @date 2024-12-18 18:48:20
 */
@Data
@TenantTable
@TableName("wms_inout_flow")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "出入库流水")
public class WmsInoutFlowEntity extends BaseEntity<WmsInoutFlowEntity> {

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
     * 仓库编号
     */
    @Schema(description = "仓库编号")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 库位编号
     */
    @Schema(description = "库位编号")
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
     * 物料数量
     */
    @Schema(description = "物料数量")
    private Double matNum;

    /**
     * 业务类型
     */
    @Schema(description = "业务类型")
    private String billType;

    /**
     * 操作类型;in入库、out出库
     */
    @Schema(description = "操作类型;in入库、out出库")
    private String operateType;

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
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshop;

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
}