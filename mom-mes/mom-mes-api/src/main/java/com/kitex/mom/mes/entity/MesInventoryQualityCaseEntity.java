package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 独立实物隔离案件。转运异常与新生产NG保留各自来源，不以运输结清或声明代表质量结案。
 */
@Data
@TenantTable
@TableName("mes_inventory_quality_case")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES实际库存质量隔离案件")
public class MesInventoryQualityCaseEntity extends BaseEntity<MesInventoryQualityCaseEntity> {
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    @Schema(description = "来源：TRANSFER或PRODUCTION_NG；NULL只解释为旧转运事实")
    private String originType;

    @Schema(description = "真实生产不良原报告，仅PRODUCTION_NG")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceUnqualifiedReportId;

    @Schema(description = "初始实物采集原账，仅PRODUCTION_NG")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productionNgCaptureId;

    @Schema(description = "初始真实入库流水，仅PRODUCTION_NG")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long initialInflowId;

    @Schema(description = "原转运单，仅TRANSFER")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long transferId;

    @Schema(description = "原异常实收操作，仅TRANSFER")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long receiptId;

    @Schema(description = "原合格生产报工，仅TRANSFER")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceReportId;

    @Schema(description = "原转运来源库存，仅TRANSFER")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceStockId;

    @Schema(description = "实际隔离根库存")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    @Schema(description = "实际实收或首次采集的认证人员")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long receiverId;

    @Schema(description = "原实收或采集人员编码")
    private String receiverCode;

    @Schema(description = "按原计量单位登记的完整初始隔离量，不随处置改写")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal quantity;

    @Schema(description = "原物料编码")
    private String matCode;

    @Schema(description = "原物料名称")
    private String matName;

    @Schema(description = "原实际批次")
    private String matBatch;

    @Schema(description = "实际实物标识；无码数量模式可为空")
    private String matBarcode;

    @Schema(description = "原计量单位，不用当前MDM覆盖")
    private String matUnit;

    @Schema(description = "实际隔离库位编码")
    private String locationCode;

    @Schema(description = "原隔离库位名称")
    private String locationName;

    @Schema(description = "异常：DAMAGED/EXPIRED/QUALITY_SUSPECT/PRODUCTION_NG")
    private String exceptionType;

    @Schema(description = "首次异常原因，最多500字符")
    private String reason;

    @Schema(description = "实物标签、位置与现场隔离证据")
    private String segregationEvidence;

    @Schema(description = "该来源的完整原证据字符串，永久不重算")
    private String sourceSnapshot;

    @Schema(description = "实际库存及永久处置账核定的当前隔离剩余量")
    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal remainingQuantity;

    @Schema(description = "待独立验证的实际预约量")
    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal reservedQuantity;

    @Schema(description = "已验证实物处置结案累计量")
    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal closedQuantity;

    @Schema(description = "原对象模式核定是否不可拆分处置")
    @TableField(exist = false)
    private Boolean indivisible;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}
