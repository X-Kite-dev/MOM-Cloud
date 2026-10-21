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
 * 变化点损失Pareto与QMS CAPA/8D之间的可靠业务链接。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_change_point_improvement")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点损失改善来源单")
public class MesChangePointImprovementEntity extends BaseEntity<MesChangePointImprovementEntity> {

    @Schema(description = "来源单编号")
    private String improvementNo;
    @Schema(description = "创建命令幂等号")
    private String requestId;
    @Schema(description = "聚合维度：CATEGORY/CONFIG/LOSS")
    private String dimension;
    @Schema(description = "聚合项编码")
    private String groupCode;
    @Schema(description = "聚合项名称")
    private String groupName;
    @Schema(description = "基线开始时间，包含")
    private LocalDateTime baselineStartTime;
    @Schema(description = "基线结束时间，不包含")
    private LocalDateTime baselineEndTime;
    @Schema(description = "基线事件数")
    private Long baselineEventCount;
    @Schema(description = "基线自动暂停事件数")
    private Long baselineAutoPauseCount;
    @Schema(description = "基线暂停失败事件数")
    private Long baselinePauseFailureCount;
    @Schema(description = "基线受影响在制数量")
    private BigDecimal baselineAffectedWipQty;
    @Schema(description = "基线放行数量")
    private BigDecimal baselineReleasedWipQty;
    @Schema(description = "基线返工数量")
    private BigDecimal baselineReworkWipQty;
    @Schema(description = "基线报废数量")
    private BigDecimal baselineScrapWipQty;
    @Schema(description = "基线损失金额")
    private BigDecimal baselineLossAmount;
    @Schema(description = "基线平均闭环分钟")
    private BigDecimal baselineAverageCloseMinutes;
    @Schema(description = "基线口径和指标SHA-256指纹")
    private String baselineFingerprint;

    @Schema(description = "发起时冻结的原始变化点与产品范围；历史缺失不补造，复测须有该依据")
    private String baselineEventSnapshot;
    @Schema(description = "CAPA标题")
    private String title;
    @Schema(description = "可验证的问题陈述")
    private String problemStatement;
    @Schema(description = "影响范围")
    private String impactScope;
    @Schema(description = "触发CAPA原因")
    private String triggerReason;
    @Schema(description = "严重度")
    private Integer severity;
    @Schema(description = "发生度")
    private Integer occurrence;
    @Schema(description = "探测度")
    private Integer detection;
    @Schema(description = "风险优先数")
    private Integer riskPriorityNo;
    @Schema(description = "跨职能团队成员")
    private String teamMembers;
    @Schema(description = "CAPA协调人工号")
    private String coordinatorCode;
    @Schema(description = "CAPA协调人姓名")
    private String coordinatorName;
    @Schema(description = "计划关闭时间")
    private LocalDateTime dueTime;
    @Schema(description = "发往QMS的永久幂等请求号")
    private String qmsRequestId;
    @Schema(description = "QMS CAPA主键")
    private Long qmsCapaId;
    @Schema(description = "QMS CAPA编号")
    private String qmsCapaNo;
    @Schema(description = "QMS CAPA当前阶段")
    private String qmsCapaStatus;
    @Schema(description = "链接状态：PENDING/LINKED/FAILED_UNKNOWN/CLOSED/CANCELLED")
    private String linkStatus;
    @Schema(description = "最近链接或同步结果")
    private String linkResult;
    @Schema(description = "最近成功链接或同步时间")
    private LocalDateTime linkTime;
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}
