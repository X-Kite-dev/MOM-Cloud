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
import java.time.LocalDateTime;

/**
 * 报工实际投料消耗分配。用于追溯实际批次以及冲销时精确恢复 used_num。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_report_material_consume")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "报工实际投料消耗分配")
public class MesReportMaterialConsumeEntity extends BaseEntity<MesReportMaterialConsumeEntity> {

    /**
     * 本次真实GOOD报告主键；不能填写NG来源或生产operation ID。
     */
    @Schema(description = "实际合格报告ID")
    private Long reportId;
    /**
     * 原合格报工请求号，关联原成功回执。
     */
    @Schema(description = "原合格报工请求号")
    private String reportRequestId;
    /**
     * 实际耗用所属派工编码，归属不能由物料批次猜测。
     */
    @Schema(description = "原派工编码")
    private String arrangeCode;
    /**
     * 精确实际投料行；一行记录本次对该来源的耗用。
     */
    @Schema(description = "实际投料ID")
    private Long feedId;
    /**
     * 原工序需求主料编码，与实际主料或替代料编码分开。
     */
    @Schema(description = "原需求物料编码")
    private String requirementMatCode;
    /**
     * 实际耗用物料编码，替代时保留真实替代料。
     */
    @Schema(description = "实际耗用物料编码")
    private String matCode;
    /**
     * 实际投料的原批次；NULL/空文本不补造。
     */
    @Schema(description = "原投料批次")
    private String matBatch;
    /**
     * 实际投料的原标识；不使用产出码覆盖原料码。
     */
    @Schema(description = "原投料标识")
    private String matBarcode;
    /**
     * 以该feed原单位记录的精确实际耗量，保存回读禁止舍入。
     */
    @Schema(description = "实际原单位耗量")
    private BigDecimal consumeNum;
    /**
     * 满足原需求单位的精确当量，等于实际耗量除以原替代比例。
     */
    @Schema(description = "原需求当量")
    private BigDecimal requirementNum;
    /**
     * 本次实际使用替代料的明确标记，不由编码差异事后猜测。
     */
    @Schema(description = "是否实际替代")
    private Boolean substituteFlag;
    /**
     * 实际耗量/需求当量比例；主料固定1，沿原BOM核验。
     */
    @Schema(description = "原实际/需求比例")
    private BigDecimal substituteRatio;
    /**
     * EFFECTIVE有效或REVERSED已恢复；只由正式冲销流程变更。
     */
    @Schema(description = "耗用分配状态")
    private String status;
    /**
     * 正式恢复动作原号，不复用生产报告请求号。
     */
    @Schema(description = "正式恢复原号")
    private String reverseRequestId;
    /**
     * 实际正式恢复时间；新耗用必须为空。
     */
    @Schema(description = "正式恢复时间")
    private LocalDateTime reverseTime;

    /**
     * 原耗用实际工厂，与认证组织及原GOOD报告一致。
     */
    @Schema(description = "实际工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
