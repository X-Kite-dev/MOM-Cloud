package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.EqualsAndHashCode;
import com.kitex.mom.plan.entity.PlnMpsPolicyEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentEntity;
import com.kitex.mom.plan.entity.PlnMpsRemainingCommitmentLineEntity;

import java.util.ArrayList;
import java.util.List;

/** 剩余生产完成责任的精确业务契约，不代表物理拆批、入库或客户交付放行。 */
public final class PlnMpsRemainingCommitmentDTO {
    private PlnMpsRemainingCommitmentDTO() {
    }

    /** 仅事务代理成功回滚后返回，通用错误或未查到原号不构成此证明。 */
    @Data
    @Accessors(chain = true)
    public static class Rejection {
        @Schema(description = "明确ROLLBACK_CONFIRMED，不能按通用code=1清理原号")
        private String outcome;
        @Schema(description = "被明确拒绝的永久请求号")
        private String requestId;
        @Schema(description = "SUBMIT/CANCEL/REVIEW/CAPTURE/FULFILLMENT_REVIEW")
        private String operationType;
        @Schema(description = "精确目标ID文本")
        private String targetId;
        @Schema(description = "规范化原业务载荷JSON，主键文本，不由客户端手填回滚结论")
        private String payloadSnapshot;
        @Schema(description = "本次业务拒绝的明确原因")
        private String message;
    }

    /** 分期预览载荷，只引用原确认观察，不接受客户端自行构造基线或改资源。 */
    @Data
    @Accessors(chain = true)
    public static class PreviewCommand {
        @Schema(description = "已独立确认的原观察ID")
        private Long observationId;
        @Schema(description = "精确原MPS桶ID")
        private Long lineId;
        @Schema(description = "预期当前生效剩余版本；首次为空，禁止跳过并发新版本")
        private Long previousCommitmentId;
        @Schema(description = "全部内部日期/精确数量桶，不是实体拆批")
        private List<Schedule> schedules;
    }

    @Data
    @Accessors(chain = true)
    @EqualsAndHashCode(callSuper = true)
    public static class Submit extends PreviewCommand {
        @Schema(description = "永久编制请求号，每个标签页/命令独立保留")
        private String requestId;
        @Schema(description = "预览原基线/政策/日桶/前版的结构意图指纹；当前推进仍重算")
        private String intentFingerprint;
        @Schema(description = "完整编制原因")
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Review {
        @Schema(description = "不可变待审版本ID")
        private Long id;
        @Schema(description = "永久独立审核请求号")
        private String requestId;
        @Schema(description = "PUBLISH或REJECT，不接受手填计算结论")
        private String decision;
        @Schema(description = "固定四项核验目录，发布须全部确认")
        private List<String> checks;
        @Schema(description = "审核业务意见")
        private String reason;
        @Schema(description = "WARN/OFF时必填风险/模式说明，不能冒充产能已验证")
        private String riskAcknowledgement;
    }

    @Data
    @Accessors(chain = true)
    public static class Cancel {
        @Schema(description = "只允许编制人取消的待审版本ID")
        private Long id;
        @Schema(description = "永久取消请求号")
        private String requestId;
        @Schema(description = "取消业务原因，原账不删除")
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Preview {
        @Schema(description = "原Q/B/R和当前推进，当前正常新增不会修改原基线")
        private BaselinePreview basis;
        @Schema(description = "实际工厂当前策略，只读")
        private PlnMpsPolicyEntity policy;
        @Schema(description = "当前生效版本，首版为空")
        private String currentCommitmentId;
        @Schema(description = "完整精确内部日桶")
        private List<Schedule> schedules;
        @Schema(description = "守住基线、前版、政策和编制载荷的结构意图指纹")
        private String intentFingerprint;
        @Schema(description = "重新核查的粗能力依据，不代表正式占用")
        private PlnMpsCapacityDTO.Preview capacity;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        @Schema(description = "不可变业务版本/审批依据")
        private PlnMpsRemainingCommitmentEntity record;
        @Schema(description = "完整不可变内部日桶")
        private List<PlnMpsRemainingCommitmentLineEntity> lines;
        @Schema(description = "当前生效指针，旧版不改写为删除或恢复")
        private String currentCommitmentId;
        @Schema(description = "本版是否仍为当前生效版本")
        private Boolean current;
    }

    @Data
    @Accessors(chain = true)
    public static class FulfillmentEvidence {
        @Schema(description = "被冻结的剩余承诺版本ID文本")
        private String commitmentId;
        @Schema(description = "精确原MPS桶ID文本")
        private String lineId;
        @Schema(description = "原案件连续业务版本")
        private Integer commitmentVersion;
        @Schema(description = "本版是否仍为当前生效版本，历史不恢复")
        private Boolean current;
        @Schema(description = "当前生效版本ID文本，用于确认替代后失效")
        private String currentCommitmentId;
        @Schema(description = "完整不可变来源基线")
        private Baseline baseline;
        @Schema(description = "当前原承诺/客户交期及完整MES逐事件依据，旧延期不被新分期掩盖")
        private PlnMpsExecutionDTO.Bucket original;
        @Schema(description = "当前逐分期精确兑现与虚拟份额，不修改实物归属")
        private Evaluation evaluation;
        @Schema(description = "完整本次观察ISO时刻")
        private String observedAt;
        @Schema(description = "该版冻结的日期截止容差")
        private Integer toleranceMinutes;
        @Schema(description = "稳定原桶/案件/当前指针/逐报工/兑现证据指纹")
        private String fingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class FulfillmentCapture {
        @Schema(description = "当前生效剩余版本ID")
        private Long commitmentId;
        @Schema(description = "永久采集请求号")
        private String requestId;
        @Schema(description = "采集业务依据")
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class FulfillmentReview {
        @Schema(description = "不可变分期观察ID")
        private Long id;
        @Schema(description = "永久独立核验号")
        private String requestId;
        @Schema(description = "CONFIRM或REJECT，不手填兑现结论")
        private String decision;
        @Schema(description = "固定SOURCE_BASELINE_VALID、QUANTITY_CONSERVED、TIME_BASIS_DISTINGUISHED、ORIGINAL_PROMISE_PRESERVED")
        private List<String> checks;
        @Schema(description = "核验业务意见")
        private String remark;
    }

    /** 仅来源/数量准备情况，不等于冻结区、能力或独立审批通过。 */
    @Data
    @Accessors(chain = true)
    public static class BaselinePreview {
        @Schema(description = "已确认原观察ID文本")
        private String observationId;
        @Schema(description = "原已发布MPS版本ID文本")
        private String versionId;
        @Schema(description = "原独立观察的截止容差分钟")
        private Integer toleranceMinutes;
        @Schema(description = "来自原确认观察的完整基线，不以当前累计覆盖原B")
        private Baseline baseline;
        @Schema(description = "当前正常推进/基线失效/来源失效/补录/待核结论；不虚构分期日期")
        private Evaluation current;
        @Schema(description = "来源数量可继续编制；不代表产能核验、发布授权或已经落正式账")
        private Boolean quantityReady;
        @Schema(description = "不可编制的具体说明")
        private String eligibilityReason;
        @Schema(description = "原基线及当前推进数量依据指纹，正式命令仍需重新取证")
        private String fingerprint;
    }

    /** 来自已核清原MPS来源的不可变计算基线；确认观察权限由领域服务校验。 */
    @Data
    @Accessors(chain = true)
    public static class Baseline {
        @Schema(description = "精确原MPS桶主键文本")
        private String lineId;
        @Schema(description = "原桶永久来源键")
        private String bucketKey;
        @Schema(description = "原产品需求号")
        private String demandNo;
        @Schema(description = "原独立根计划ID文本")
        private String rootIssueId;
        @Schema(description = "原独立根计划号")
        private String rootIssueNo;
        @Schema(description = "原产品编码")
        private String productCode;
        @Schema(description = "权威产品单位，与每份冻结报工单位一致，不隐式换算")
        private String productUnit;
        @Schema(description = "原计划数量Q，精确文本")
        private String originalQuantity;
        @Schema(description = "基线来源有效报工B，非完工结算或入库量")
        private String baselineQuantity;
        @Schema(description = "原始剩余承诺R=Q-B，最多8位小数")
        private String remainingQuantity;
        @Schema(description = "原MPS计划完成日，不被新分期覆盖")
        private String plannedDate;
        @Schema(description = "原客户交期，不被新分期覆盖")
        private String sourceDueDate;
        @Schema(description = "原可信粗能力资源，不授权跨资源移派")
        private String capacityResourceCode;
        @Schema(description = "原观察采集ISO时刻，来源各自的MES查询起点另行冻结")
        private String observedAt;
        @Schema(description = "完整来源身份/原分配量/每来源补录边界")
        private List<SourceIdentity> sources;
        @Schema(description = "基线全部有效来源贡献及原事实指纹，普通新增不加入此集合")
        private List<PlnMpsExecutionDTO.ProductionEvent> events;
    }

    @Data
    @Accessors(chain = true)
    public static class SourceIdentity {
        @Schema(description = "物理工单主键文本")
        private String taskId;
        @Schema(description = "直接/APS来源类型")
        private String relationType;
        @Schema(description = "原APS分配ID文本，直接来源为空")
        private String allocationId;
        @Schema(description = "APS原根计划ID文本，直接来源为空")
        private String sourceIssueId;
        @Schema(description = "本来源原产品编码")
        private String productCode;
        @Schema(description = "原精确分配量，不以当前完成量替代")
        private String sourceQuantity;
        @Schema(description = "完整来源/唯一末序身份指纹")
        private String sourceFingerprint;
        @Schema(description = "原MES一致性查询开始边界，不以PLAN响应结束时间判断补录")
        private String baselineEventCutoff;
    }

    @Data
    @Accessors(chain = true)
    public static class Schedule {
        @Schema(description = "从1连续的内部责任桶序号，最多100桶")
        private Integer bucketNo;
        @Schema(description = "内部生产完成承诺ISO日期，一日一桶")
        private String promisedDate;
        @Schema(description = "本桶正责任量，十进制文本；全部桶严格等于原R")
        private String quantity;
    }

    @Data
    @Accessors(chain = true)
    public static class Share {
        @Schema(description = "真实末序报工ID文本")
        private String reportId;
        @Schema(description = "物理工单ID文本")
        private String taskId;
        @Schema(description = "原来源分配ID文本，直接来源为空")
        private String allocationId;
        @Schema(description = "本内部桶的精确虚拟份额，不改实物SN或MES来源")
        private String quantity;
    }

    @Data
    @Accessors(chain = true)
    public static class Fulfillment {
        @Schema(description = "内部承诺桶序号")
        private Integer bucketNo;
        @Schema(description = "内部承诺日期")
        private String promisedDate;
        @Schema(description = "该版原始承诺量")
        private String promisedQuantity;
        @Schema(description = "当前有效产出按最早日期优先归属的精确量")
        private String fulfilledQuantity;
        @Schema(description = "本内部桶当前未兑现量")
        private String remainingQuantity;
        @Schema(description = "首次达到桶累计阈值的本轮有效事件时刻；纠正后可回退，不改旧观察")
        private String reachedAt;
        @Schema(description = "DEVICE_EVENT/REPORT_REGISTERED/NONE，登记结论不是实物完成证明")
        private String timeBasis;
        @Schema(description = "ON_TIME/LATE/REGISTERED_ON_TIME/REGISTERED_LATE/PENDING/OVERDUE")
        private String status;
        @Schema(description = "完整精确虚拟份额；不代表新增SN谱系")
        private List<Share> shares = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Evaluation {
        @Schema(description = "ACTIVE/FULFILLED/BASELINE_STALE/SOURCE_STALE/BACKFILLED_BASELINE/DATA_GAP")
        private String result;
        @Schema(description = "当前来源量C；不能用本字段替代已核实剩余责任")
        private String currentQuantity;
        @Schema(description = "仅基线/来源/新增事件均可信时给出的当前Q-C；未知不填零")
        private String remainingQuantity;
        @Schema(description = "逐桶当前兑现结论；失效时不伪造可信份额")
        private List<Fulfillment> buckets = new ArrayList<>();
        @Schema(description = "失效/待核原因，正式RCCP不得忽略这些责任")
        private List<String> gaps = new ArrayList<>();
    }
}
