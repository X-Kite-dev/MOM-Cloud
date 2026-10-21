package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnSchedulingEffectObservationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectReviewEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 已发布APS候选方案的MES执行效果回评命令与视图。 */
public final class PlnSchedulingEffectReviewDTO {
    private PlnSchedulingEffectReviewDTO() {}

    @Data
    public static class Query {
        @Schema(description = "排产情景主键；工作台精确定位时使用，不能用模糊情景号替代")
        private Long scenarioId;
        private String scenarioNo;
        private String triggerType;
        private String candidateCode;
        private String reviewStatus;
    }

    @Data
    public static class CaptureCommand {
        private Long scenarioId;
        @Schema(description = "永久幂等请求号")
        private String requestId;
        @Schema(description = "计划/实际允许偏差分钟数，默认30，范围0至1440")
        private Integer toleranceMinutes;
        @Schema(description = "人工重评原因")
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private PlnSchedulingEffectReviewEntity review;
        private List<PlnSchedulingEffectObservationEntity> observations;
    }

    /** actualSnapshot 中的逐工序证据结构，字段定义固定并随 scoreFormulaVersion 版本化。 */
    @Data
    @Accessors(chain = true)
    public static class OperationEvidence {
        private Long issueId;
        private String issueNo;
        private String productCode;
        private String productName;
        private String processCode;
        private String processName;
        private Integer operationSeq;
        private String resourceCode;
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private Boolean mesTaskPresent;
        private Boolean matched;
        private String mesTaskCode;
        @Schema(description = "本次MES权威状态的精确工序任务编码，不与物理工单混用")
        private String mesProduceTaskCode;
        @Schema(description = "本次MES权威状态工序任务ID，文本")
        private String mesProduceTaskId;
        @Schema(description = "本次MES状态原开工ISO文本，保留亚秒")
        private String mesActualStartTimeText;
        @Schema(description = "本次MES状态原完工ISO文本，保留亚秒")
        private String mesActualFinishTimeText;
        private String mesStatus;
        @Schema(description = "MES判定该工序是否仍允许调整计划时刻和资源")
        private Boolean movable;
        @Schema(description = "MES禁止移动该工序时返回的客观阻断原因")
        private String movementBlockedReason;
        private LocalDateTime actualStartTime;
        private LocalDateTime actualFinishTime;
        private String completeQuantity;
        private Long startDeviationMinutes;
        private Long finishDeviationMinutes;
        private Boolean startAdherent;
        private Boolean finishAdherent;
        private String gapReason;
        @Schema(description = "新连续生产段证据；旧观察未采集时为null，不回填")
        private DurationEvidence durationEvidence;
    }

    /** 生产段分钟独立证据，不改变开完工评分，不声明纯加工、停机或人工人时。 */
    @Data
    @Accessors(chain = true)
    public static class DurationEvidence {
        @Schema(description = "APS_RUN_SEGMENTS_V1历史或APS_RUN_SEGMENTS_V2新观察")
        private String evidenceVersion;
        @Schema(description = "当前工装换型归属VERIFIED/UNBOUND/CONTESTED/DATA_GAP，不等于完整APS换型活动")
        private String changeoverStatus;
        @Schema(description = "核定的工装换型计时分钟；未知为null，不给换型收益差值")
        private String actualToolingChangeoverMinutes;
        @Schema(description = "归属/原源/操作及与RUN重叠核查的全部缺口")
        private List<String> changeoverGaps;
        @Schema(description = "COMPARABLE/VERIFIED_NOT_COMPARABLE/DATA_GAP/IN_PROGRESS/UNAVAILABLE")
        private String status;
        @Schema(description = "计划运行分钟，原下达快照未知时为null")
        private String plannedRunMinutes;
        @Schema(description = "原计划固定准备分钟，已从原setup中扣除其包含的换型，不重复计数")
        private String plannedSetupMinutes;
        @Schema(description = "原计划拆卸分钟")
        private String plannedDisassemblyMinutes;
        @Schema(description = "原计划顺序换型分钟，当前未建立唯一工序实绩绑定")
        private String plannedChangeoverMinutes;
        @Schema(description = "完整已核验RUN段分钟，精确十进制文本，非纯加工遥测")
        private String runSegmentMinutes;
        @Schema(description = "完整墙钟分钟，不当加工量")
        private String wallClockMinutes;
        @Schema(description = "派工内部段间暂停间隙分钟，不推断停机原因")
        private String pauseGapMinutes;
        @Schema(description = "顺序派工之间间隙分钟")
        private String interDispatchGapMinutes;
        @Schema(description = "区间整秒截断导致的墙钟精度余差分钟，不解释为暂停/停机")
        private String precisionRemainderMinutes;
        @Schema(description = "只在同量同资源、完整段且固定准备/拆卸/未唯一归属换型均为0时给RUN与计划运行差")
        private String runDifferenceMinutes;
        @Schema(description = "所有不可核验或不可比原因")
        private List<String> gaps;
        @Schema(description = "MES原完整证据指纹")
        private String sourceFingerprint;
        @Schema(description = "本次MES全部派工/段完整证据JSON，原观察不改")
        private String sourceSnapshot;
    }
}
