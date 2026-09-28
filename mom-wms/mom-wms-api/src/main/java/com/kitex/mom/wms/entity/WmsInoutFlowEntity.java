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
import java.time.LocalDateTime;

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

    @Schema(description = "库存首次形成时的WMS入库单ID快照")
    private Long sourceInBillId;

    @Schema(description = "库存首次形成时的WMS入库单明细ID快照")
    private Long sourceInBillDetailId;

    @Schema(description = "MES成品入库永久请求号快照")
    private String sourceMesInboundRequestId;

    @Schema(description = "来源采购订单号快照")
    private String sourceOrderNo;

    @Schema(description = "来源采购订单明细ID快照")
    private Long sourceOrderDetailId;

    @Schema(description = "来源采购收料单ID快照")
    private Long sourceReceiptId;

    @Schema(description = "来源采购收料批次明细ID快照")
    private Long sourceReceiptDetailId;

    /**
     * 单据id
     */
    @Schema(description = "单据id")
    private Long billId;

    @Schema(description = "出入库单明细ID；精确追溯到本次实际操作行")
    private Long billDetailId;

    @Schema(description = "调拨源实时库存ID")
    private Long sourceStockId;

    @Schema(description = "库存证据类型：SALES_SHIPMENT等；NULL为历史普通流水")
    private String stockProofType;

    @Schema(description = "调拨入库/取消返还对应的发起出库流水ID")
    private Long sourceFlowId;

    @Schema(description = "调拨流水动作：ISSUE/RECEIVE/CANCEL_RETURN/OVER_ISSUE_ADJUST/OVER_ACCEPT/OVER_RETURN")
    private String transferAction;

    @Schema(description = "接收、取消或差异处置命令永久幂等号")
    private String operationRequestId;

    @Schema(description = "分批接收记录ID")
    private Long transferReceiptId;

    @Schema(description = "短溢收差异单ID")
    private Long varianceCaseId;

    @Schema(description = "短溢收差异明细ID")
    private Long varianceLineId;

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

    @Schema(description = "源库存首次入库时间快照")
    private LocalDateTime stockInTime;

    @Schema(description = "质量结论快照")
    private String qualityResult;

    @Schema(description = "标准质量状态快照")
    private String qualityStatus;

    @Schema(description = "质量隔离单号快照")
    private String qualityHoldNo;

    @Schema(description = "质量状态来源类型快照")
    private String qualitySourceType;

    @Schema(description = "质量状态来源业务ID快照")
    private String qualitySourceId;

    @Schema(description = "是否要求质检快照")
    private String isQualityTest;

    @Schema(description = "质检任务ID快照")
    private Long qualityTaskId;

    @Schema(description = "首次成品入库实际依赖的QMS任务ID JSON数组快照")
    private String sourceQualityTaskIds;

    @Schema(description = "首次成品入库QMS质量证据SHA-256指纹快照")
    private String sourceQualityEvidenceFingerprint;

    @Schema(description = "首次成品入库QMS质量证据快照")
    private String sourceQualityEvidenceSnapshot;

    @Schema(description = "有效期检测状态快照")
    private Boolean expirationStatus;

    @Schema(description = "呆滞物料检测状态快照")
    private Boolean slowMaterialStatus;

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
