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

/**
 * 一次真实生产的总投料永久账；独立于旧单GOOD/NG分配，不是库存或质量放行。
 */
@Data
@TenantTable
@TableName("mes_production_feed_batch")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "一次真实生产的总投料永久账；独立于旧单GOOD/NG分配，不是库存或质量放行")
public class MesProductionFeedBatchEntity extends BaseEntity<MesProductionFeedBatchEntity> {
    /**
     * 本次内部生产原号，租户内永久唯一，不使用某份GOOD/NG报告号代替。
     */
    @Schema(description = "本次内部生产原号，租户内永久唯一，不使用某份GOOD/NG报告号代替")
    private String requestId;

    /**
     * 本次真实认证用户主键，不由请求声明授予身份。
     */
    @Schema(description = "本次真实认证用户主键，不由请求声明授予身份")
    private Long actorId;

    /**
     * 实际父生产工单编码。
     */
    @Schema(description = "实际父生产工单编码")
    private String taskCode;

    /**
     * 实际工序任务编码。
     */
    @Schema(description = "实际工序任务编码")
    private String produceTaskCode;

    /**
     * 实际派工编码。
     */
    @Schema(description = "实际派工编码")
    private String arrangeCode;

    /**
     * 本次全部产出的原生产基本单位。
     */
    @Schema(description = "本次全部产出的原生产基本单位")
    private String productionUnit;

    /**
     * 全部正量GOOD及NG产出原单位数量合计，仅用于总耗料。
     */
    @Schema(description = "全部正量GOOD及NG产出原单位数量合计，仅用于总耗料")
    private BigDecimal processedQuantity;

    /**
     * MATERIAL_RECORDED仅表示总耗料及份额已记录。
     */
    @Schema(description = "MATERIAL_RECORDED仅表示总耗料及份额已记录")
    private String status;

    /**
     * 独立总投料证据版本，不重算旧257原文。
     */
    @Schema(description = "独立总投料证据版本，不重算旧257原文")
    private String evidenceVersion;

    /**
     * 完整固定字段证据原文，含全部真实产出、耗用根和归属边。
     */
    @Schema(description = "完整固定字段证据原文，含全部真实产出、耗用根和归属边")
    private String evidenceSnapshot;

    /**
     * 证据版本与原字符串的UTF-8 SHA-256。
     */
    @Schema(description = "证据版本与原字符串的UTF-8 SHA-256")
    private String evidenceFingerprint;

    /**
     * 真实工厂，服务端按已认证上下文确定。
     */
    @Schema(description = "真实工厂，不能由页面声明授权")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
