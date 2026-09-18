package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 线边库投料记录 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-02-20 17:22:19
 */
@Data
@TenantTable
@TableName("mes_warehouse_feed")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "线边库投料记录")
public class MesWarehouseFeedEntity extends BaseEntity<MesWarehouseFeedEntity> {

    /**
     * 任务编码
     */
    @NotBlank(message = "任务编码为空")
    @Schema(description = "任务编码")
    private String arrangeCode;

    /**
     * 工序任务编码
     */
    @Schema(description = "工序任务编码")
    private String produceTaskCode;

    /**
     * 生产工单编码
     */
    @NotBlank(message = "生产工单编码为空")
    @Schema(description = "生产工单编码")
    private String taskCode;

    /**
     * 物料编码
     */
    @NotBlank(message = "物料编码为空")
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "WMS物料预留单号")
    private String reservationNo;

    @Schema(description = "BOM需求物料编码；使用替代料时与实际物料编码不同")
    private String requirementMatCode;

    @Schema(description = "是否为替代料投料")
    private Boolean substituteFlag;

    @Schema(description = "替代比例：1个需求单位对应的实际物料用量")
    private BigDecimal substituteRatio;

    @Schema(description = "投料时冻结的替代策略快照")
    private String substitutePolicySnapshot;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 批次号
     */
    @NotBlank(message = "批次号为空")
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
    @NotBlank(message = "供应商编码为空")
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
     * 投料数量
     */
    @NotNull(message = "投料数量为空")
    @Schema(description = "投料数量")
    private BigDecimal feedNum;

    /**
     * 消耗数量
     */
    @Schema(description = "消耗数量")
    private BigDecimal usedNum;

    /**
     * 投料时间
     */
    @Schema(description = "投料时间")
    private LocalDateTime feedTime;

    /**
     * 投料人员
     */
    @Schema(description = "投料人员")
    private String feedPersonCode;

    /**
     * 投料人员姓名
     */
    @Schema(description = "投料人员姓名")
    private String feedPersonName;

    /**
     * 是否锁定
     */
    @Schema(description = "是否锁定")
    private Boolean isLock;

    /**
     * 状态
     */
    @Schema(description = "状态")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    /**
     * 质检任务编号
     */
    @Schema(description = "质检任务编号")
    private Long inspectionTaskId;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String inspectionResult;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
