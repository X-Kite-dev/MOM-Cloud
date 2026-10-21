package com.kitex.mom.mes.entity;

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

/**
 * 聚合报工部分纠正的不可变数量分配行。
 *
 * <p>每行指向原产出流水、原投料消耗或原包装内容，记录纠正前累计量、本次量和剩余量。
 * 原始正向事实不改写，后续纠正据此计算尚可恢复数量并防止重复冲减。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_report_quantity_correction_line")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "聚合报工部分纠正分配行")
public class MesReportQuantityCorrectionLineEntity extends BaseEntity<MesReportQuantityCorrectionLineEntity> {

    @Schema(description = "冲销/纠正申请ID")
    private Long reverseRequestId;
    @Schema(description = "永久执行请求号")
    private String operationRequestId;
    @Schema(description = "原报工ID")
    private Long reportId;
    @Schema(description = "OUTPUT_FLOW/MATERIAL_FLOW/MATERIAL_CONSUME/PACKAGE_CONTENT")
    private String allocationType;
    @Schema(description = "原始事实行ID")
    private Long sourceId;
    @Schema(description = "原始事实编码，如箱码、批次或库存流水号")
    private String sourceCode;
    @Schema(description = "原始事实数量")
    private BigDecimal originalQuantity;
    @Schema(description = "本次执行前已纠正数量")
    private BigDecimal correctedBeforeQuantity;
    @Schema(description = "本次纠正数量")
    private BigDecimal correctionQuantity;
    @Schema(description = "本次执行后剩余数量")
    private BigDecimal remainingQuantity;
    @Schema(description = "原报工冻结单位")
    private String quantityUnit;
    @Schema(description = "申请冻结的完整事实指纹")
    private String evidenceFingerprint;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
