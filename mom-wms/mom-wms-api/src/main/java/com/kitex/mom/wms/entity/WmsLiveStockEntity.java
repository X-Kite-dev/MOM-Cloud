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

import java.math.BigDecimal;
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

    @Schema(description = "库存货权：OWNED/CUSTOMER_SUPPLIED/CONSIGNED")
    private String stockOwnerType;

    @Schema(description = "客供客户或寄售供应商编码")
    private String stockOwnerCode;

    @Schema(description = "首次入库来源业务单号")
    private String sourceBusinessNo;

    @Schema(description = "首次形成该库存的WMS入库单ID；库存移动、拆分和受控恢复后保持不变")
    private Long sourceInBillId;

    @Schema(description = "首次形成该库存的WMS入库单明细ID；用于成品实物红冲精确追溯")
    private Long sourceInBillDetailId;

    @Schema(description = "MES成品入库永久请求号；仅MES成品入库来源库存填写")
    private String sourceMesInboundRequestId;

    @Schema(description = "来源采购订单号；采购入库库存必填")
    private String sourceOrderNo;

    @Schema(description = "来源采购订单明细ID；采购入库库存必填")
    private Long sourceOrderDetailId;

    @Schema(description = "来源采购收料单ID；采购入库库存必填")
    private Long sourceReceiptId;

    @Schema(description = "来源采购收料批次明细ID；采购入库库存必填")
    private Long sourceReceiptDetailId;

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
     * 物料条码或产品序列号；是否为不可拆分单件由serialManaged确定。
     */
    @Schema(description = "物料条码或产品序列号")
    private String matBarcode;

    @Schema(description = "入库时确定的单件序列号管理标记；库存移动和质量处置时保持不变")
    private Boolean serialManaged;

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

    @TableField(exist = false)
    @Schema(description = "扣除WMS活动预留后的计划可用数量；供MRP读取，不改变实物数量")
    private BigDecimal planAvailableNum;

    @TableField(exist = false)
    @Schema(description = "扫码移库或调拨可移动量；逐库存行扣除生产和销售活动预留，不改变实物数量")
    private BigDecimal operationAvailableNum;

    @TableField(exist = false)
    @Schema(description = "扫码选中的精确来源库存行ID，以字符串返回以免移动端丢失Long精度")
    private String sourceStockId;

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

    @Schema(description = "标准质量状态：PENDING/HOLD/QUALIFIED/CONCESSION/REWORK/SCRAP_PENDING/RETURN_PENDING")
    private String qualityStatus;

    @Schema(description = "质量隔离单号")
    private String qualityHoldNo;

    @Schema(description = "质量状态来源类型")
    private String qualitySourceType;

    @Schema(description = "质量状态来源业务ID")
    private String qualitySourceId;

    @Schema(description = "是否要求质检；0否、1是")
    private String isQualityTest;

    @Schema(description = "质检任务id")
    private Long qualityTaskId;

    @Schema(description = "首次成品入库实际依赖的QMS任务ID JSON数组")
    private String sourceQualityTaskIds;

    @Schema(description = "首次成品入库QMS质量证据SHA-256指纹")
    private String sourceQualityEvidenceFingerprint;

    @Schema(description = "首次成品入库QMS质量证据快照")
    private String sourceQualityEvidenceSnapshot;

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
