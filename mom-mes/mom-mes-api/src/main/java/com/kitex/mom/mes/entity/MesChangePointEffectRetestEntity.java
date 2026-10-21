package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CAPA关闭后同口径损失复测的不可变事实及独立复核账。
 * 计算事实不允许人工编辑，终态只允许登记QMS可靠复开回执。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_change_point_effect_retest")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点CAPA关闭后效果复测")
public class MesChangePointEffectRetestEntity extends BaseEntity<MesChangePointEffectRetestEntity> {

    @Schema(description = "复测申请编号")
    private String retestNo;

    @Schema(description = "永久创建请求号")
    private String requestId;

    @Schema(description = "创建命令指纹，同号异内容拒绝")
    private String requestFingerprint;

    @Schema(description = "冻结基线来源单主键")
    private Long improvementId;

    @Schema(description = "冻结基线来源单编号")
    private String improvementNo;

    @Schema(description = "权威QMS CAPA主键")
    private Long qmsCapaId;

    @Schema(description = "申请时已关闭的CAPA周期")
    private Integer capaCycleNo;

    @Schema(description = "申请时权威CAPA关闭时间")
    private LocalDateTime capaClosedTime;

    @Schema(description = "QMS关闭状态及周期证据指纹")
    private String capaFingerprint;

    @Schema(description = "CAPA关闭依据、人员与周期完整快照")
    private String capaSnapshot;

    @Schema(description = "复测窗口开始，包含；不得早于CAPA关闭及基线结束")
    private LocalDateTime windowStartTime;

    @Schema(description = "复测窗口结束，不包含；必须已结束")
    private LocalDateTime windowEndTime;

    @Schema(description = "EVENT_DAILY事件每日频次、LOSS_DAILY确认损失每日金额或PAUSE_FAILURE_RATE暂停失败率")
    private String metric;

    @Schema(description = "相对于冻结基线要求的下降百分比，0至100")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal requiredReductionPercent;

    @Schema(description = "每个基线产品要求的最少有效报工事实数；不是生产数量")
    private Long minimumReportSamples;

    @Schema(description = "未达标复开时新周期关闭期限天数，1至365")
    private Integer reopenDueDays;

    @Schema(description = "冻结基线指标值")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal beforeValue;

    @Schema(description = "复测实测指标；不可核验时保持未知")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal afterValue;

    @Schema(description = "按冻结下降目标计算的上限")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal targetValue;

    @Schema(description = "实际下降百分比，负值表示恶化；未知不得当0")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal actualReductionPercent;

    @Schema(description = "复测同分组原始事件行数，包含作废事实用于漂移检测；指标不计作废")
    private Long observedEventCount;

    @Schema(description = "复测尚未核清在制的事件数")
    private Long unclearedEventCount;

    @Schema(description = "当前工厂、冻结产品范围内有效报工事实数")
    private Long reportSampleCount;

    @Schema(description = "原始变化点、有效报工、产品覆盖及缺口完整快照")
    private String evidenceSnapshot;

    @Schema(description = "完整复测证据指纹，复核前必须重新取证")
    private String evidenceFingerprint;

    @Schema(description = "计算结论PASS/FAIL/INSUFFICIENT，不能人工改写")
    private String calculatedResult;

    @Schema(description = "观察窗口、基线、报工覆盖或未核清事实缺口")
    private String gaps;

    @Schema(description = "PENDING/PASS/FAIL/INSUFFICIENT/REJECTED/STALE")
    private String status;

    @Schema(description = "永久复核命令请求号")
    private String reviewRequestId;

    @Schema(description = "独立复核人账号")
    private String reviewedBy;

    @Schema(description = "独立复核人姓名快照")
    private String reviewedByName;

    @Schema(description = "独立复核时间")
    private LocalDateTime reviewedTime;

    @Schema(description = "独立复核说明及现场同口径核验依据")
    private String reviewRemark;

    @Schema(description = "复核固定检查项JSON")
    private String reviewChecks;

    @Schema(description = "FAIL对应QMS复开的永久派生请求号")
    private String reopenRequestId;

    @Schema(description = "NOT_REQUIRED/PENDING/FAILED_UNKNOWN/APPLIED")
    private String reopenStatus;

    @Schema(description = "最近复开结果，远端不明时保留原请求号")
    private String reopenResult;

    @Schema(description = "最后一次复开结果登记时间")
    private LocalDateTime reopenTime;

    @Schema(description = "实际工厂编码，禁止请求扩围")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
