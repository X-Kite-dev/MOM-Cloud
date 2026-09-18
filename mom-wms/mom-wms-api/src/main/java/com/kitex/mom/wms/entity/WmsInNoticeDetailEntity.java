package com.kitex.mom.wms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
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
 * 入库通知单
 *
 * @author kitex
 * @date 2024-12-10 15:11:37
 */
@Data
@TenantTable
@TableName("wms_in_notice_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "wms_in_notice_detail")
public class WmsInNoticeDetailEntity extends BaseEntity<WmsInNoticeDetailEntity> {

    /**
     * 通知单id
     */
    @ExcelProperty("通知单号")
    @Schema(description = "通知单id")
    private Long noticeId;

    /**
     * 物料编码
     */
    @ExcelProperty("物料编码")
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "BOM需求物料编码")
    private String requirementMatCode;

    @Schema(description = "BOM需求物料名称")
    private String requirementMatName;

    @Schema(description = "是否为替代料")
    private Boolean substituteFlag;

    @Schema(description = "替代比例")
    private java.math.BigDecimal substituteRatio;

    @Schema(description = "WMS物料预留单号；生产退料时用于回补原预留")
    private String reservationNo;

    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 计量单位
     */
    @ExcelIgnore
    @Schema(description = "计量单位")
    private String matUnit;

    /**
     * 规格型号
     */
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料分组
     */
    @ExcelProperty("物料分组")
    @Schema(description = "物料分组")
    private String matGroup;

    /**
     * 物料分组名称
     */
    @ExcelProperty("物料分组名称")
    @Schema(description = "物料分组名称")
    private String matGroupName;

    /**
     * 通知入库量
     */
    @ExcelProperty("通知入库量")
    @Schema(description = "通知入库量")
    private Double noticeInNum;

    /**
     * 通知已入库量
     */
    @ExcelProperty("通知已入库量")
    @Schema(description = "通知已入库量")
    private Double inNum;

    /**
     * 通知未入库量
     */
    @ExcelProperty("通知未入库量")
    @Schema(description = "通知未入库量")
    private Double notInNum;

    /**
     * 入库仓编码
     */
    @ExcelProperty("入库仓编码")
    @Schema(description = "入库仓编码")
    private String warehouseCode;

    /**
     * 入库仓名称
     */
    @ExcelProperty("入库仓名称")
    @Schema(description = "入库仓名称")
    private String warehouseName;

    /**
     * 生产日期
     */
    @ExcelProperty("生产日期")
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期
     */
    @ExcelProperty("有效期")
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截至日期
     */
    @ExcelProperty("有效截至日期")
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 供应商编码
     */
    @ExcelProperty("供应商编码")
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ExcelProperty("供应商名称")
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 批次
     */
    @ExcelProperty("批次")
    @Schema(description = "批次")
    private String matBatch;

    /**
     * 包装规格
     */
    @ExcelProperty("包装规格")
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 包装单位
     */
    @ExcelProperty("包装单位")
    @Schema(description = "包装单位")
    private String operateUnit;

    /**
     * 包装单位数量/操作数量
     */
    @ExcelIgnore
    @Schema(description = "包装单位数量/操作数量")
    private Double operateNum;

    /**
     * 车间名称
     */
    @ExcelProperty("车间名称")
    @Schema(description = "车间名称")
    private String workshop;

    /**
     * 质检任务id
     */
    @Schema(description = "质检任务id")
    private Long qualityTaskId;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 业务单据id;采购订单、生产加工单、外协订单
     */
    @ExcelIgnore
    @Schema(description = "业务单据id;采购订单、生产加工单、外协订单")
    private Long documentId;

    /**
     * 业务单据类型;采购、生产、外协等
     */
    @ExcelIgnore
    @Schema(description = "业务单据类型;采购、生产、外协等")
    private String documentTable;

    /**
     * 扩展字段
     */
    @ExcelIgnore
    @Schema(description = "扩展字段")
    private String expansion;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @ExcelIgnore
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 状态
     */
    @ExcelProperty("状态")
    @TableField(exist = false)
    @Schema(description = "状态")
    private String state;

    /**
     * 单据类型
     */
    @ExcelIgnore
    @TableField(exist = false)
    @Schema(description = "单据类型")
    private String billType;

}
