package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** MPS日桶执行兑现与明确处置路由，所有数量采用十进制文本，主键不转浮点。 */
public final class PlnMpsExecutionDTO {
    private PlnMpsExecutionDTO() {
    }

    @Data
    public static class Capture {
        @Schema(description = "已发布/已替代的MPS版本主键")
        private Long versionId;
        @Schema(description = "永久采集请求号，最多100字")
        private String requestId;
        @Schema(description = "日桶末尾再延长的容差分钟，0至1440")
        private Integer toleranceMinutes;
        @Schema(description = "本次采集业务原因，最多500字")
        private String reason;
    }

    @Data
    public static class Review {
        @Schema(description = "观察主键")
        private Long id;
        @Schema(description = "核验永久请求号")
        private String requestId;
        @Schema(description = "CONFIRM/REJECT，不允许修改权威计算结论")
        private String decision;
        @Schema(description = "独立核验及后续处理依据，最多2000字")
        private String remark;
        @Schema(description = "SOURCE_MATCHED、QUANTITY_SEPARATED、DEVIATION_ROUTED三项固定核验")
        private List<String> checks;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        @Schema(description = "MPS主键十进制文本")
        private String versionId;
        @Schema(description = "版本号")
        private String versionNo;
        @Schema(description = "版本当前PUBLISHED/SUPERSEDED状态")
        private String versionStatus;
        @Schema(description = "实际工厂")
        private String factoryCode;
        @Schema(description = "当前采集ISO本地时刻，稳定指纹不包含每次刷新时刻")
        private String observedAt;
        @Schema(description = "日桶截止容差分钟")
        private Integer toleranceMinutes;
        @Schema(description = "CLEAR/DEVIATION/DATA_GAP，不是质量或交付放行")
        private String result;
        @Schema(description = "稳定业务证据指纹")
        private String fingerprint;
        @Schema(description = "逐桶根计划与精确MES来源对照")
        private List<Bucket> buckets;
    }

    @Data
    @Accessors(chain = true)
    public static class Bucket {
        @Schema(description = "MPS桶主键文本")
        private String lineId;
        @Schema(description = "桶来源键")
        private String bucketKey;
        @Schema(description = "需求号")
        private String demandNo;
        @Schema(description = "桶序号")
        private Integer bucketNo;
        @Schema(description = "产品编码，禁止跨产品汇总产量")
        private String productCode;
        @Schema(description = "当前实际组织产品需求的权威单位；缺失不猜件，不用于静默单位换算")
        private String productUnit;
        @Schema(description = "MPS全量承诺数量文本")
        private String planQuantity;
        @Schema(description = "MPS计划完工日")
        private String plannedDate;
        @Schema(description = "原始客户交期，不能被计划日期替换")
        private String sourceDueDate;
        @Schema(description = "当前活动承诺，退役历史不再视作待生产")
        private Boolean active;
        @Schema(description = "冻结RCCP资源编码，不冒充现场实际资源")
        private String capacityResourceCode;
        @Schema(description = "冻结RCCP预测占用分钟，不等同实际加工耗时")
        private String capacityDemandMinutes;
        @Schema(description = "独立根生产计划主键文本")
        private String rootIssueId;
        @Schema(description = "独立根生产计划号")
        private String rootIssueNo;
        @Schema(description = "根生产计划状态")
        private String rootStatus;
        @Schema(description = "PLAN已接收累计完成字段；当前MES路径由入库正负回传更新，不能当作报工量")
        private String planReceivedQuantity;
        @Schema(description = "唯一末序当前有效报工的来源归属量；缺完整核定或核定失效时未知，不等待工单完工结算")
        private String completedQuantity;
        @Schema(description = "MES精确来源入库量；不是合格交付量")
        private String inStockQuantity;
        @Schema(description = "报工未完成数量，归属未确认时未知")
        private String remainingQuantity;
        @Schema(description = "最早真实开工ISO时刻")
        private String actualStartTime;
        @Schema(description = "全范围已完工才给出最晚真实完工ISO时刻")
        private String actualFinishTime;
        @Schema(description = "是否有执行事实，未知不填false")
        private Boolean started;
        @Schema(description = "是否已全量完成报工，不等于交付")
        private Boolean completed;
        @Schema(description = "是否超出计划日末尾和容差仍未全量完工，未知不填false")
        private Boolean overdue;
        @Schema(description = "全量完成报工是否晚于MPS原承诺日末尾及容差")
        private Boolean completedAfterPlannedDate;
        @Schema(description = "已完成是否晚于原客户交期，未知不填false")
        private Boolean completedAfterCustomerDue;
        @Schema(description = "确定性处置路径，只导航评估，不直接发布或移动生产")
        private String route;
        @Schema(description = "可执行下一步说明")
        private String nextStep;
        @Schema(description = "取证/来源结构缺口")
        private List<String> gaps;
        @Schema(description = "数量归属或入库回传缺口，与工序执行状态分开")
        private List<String> quantityGaps;
        @Schema(description = "直接工单与APS来源分配，完整原始执行证据")
        private List<SourceLink> links;
        @Schema(description = "原MPS、根计划及九类质量数量的不可变原始证据")
        private String planSnapshot;
    }

    /** PLAN边界投影，不让PLAN API反向依赖MES API形成模块循环。 */
    @Data
    @Accessors(chain = true)
    public static class SourceLink {
        @Schema(description = "MES工单主键文本")
        private String taskId;

        @Schema(description = "工单编号")
        private String taskCode;

        @Schema(description = "产品编码")
        private String productCode;

        @Schema(description = "直接来源或APS分配来源")
        private String relationType;

        @Schema(description = "APS分配行主键文本")
        private String allocationId;

        @Schema(description = "APS分配账根生产计划主键文本")
        private String sourceIssueId;

        @Schema(description = "精确来源计划量")
        private String sourceQuantity;

        @Schema(description = "按当前来源依据归属的有效末序报工量；不是工单完工结算、入库或质量净放行量，未知保留null")
        private String completedQuantity;

        @Schema(description = "精确来源入库量")
        private String inStockQuantity;

        @Schema(description = "物理工单总报工量，仅展示不跨来源累加")
        private String physicalCompletedQuantity;
        @Schema(description = "工单正式完工结算字段，与在制有效末序报工分开")
        private String settledCompletedQuantity;
        @Schema(description = "DIRECT/EXPLICIT/UNVERIFIED，独占或凭证核定；物理满量不证明来源")
        private String quantityBasis;

        @Schema(description = "当前工单状态")
        private String status;

        @Schema(description = "精确实际开工ISO时刻")
        private String actualStartTime;

        @Schema(description = "精确实际完工ISO时刻")
        private String actualFinishTime;

        @Schema(description = "是否已进入执行事实")
        private Boolean started;

        @Schema(description = "实际工序数")
        private Integer operationCount;

        @Schema(description = "当前暂停工序数")
        private Long pausedOperationCount;

        @Schema(description = "全部工序是否尚可评估移动，非发布授权")
        private Boolean allOperationsMovable;

        @Schema(description = "原始工单、工序和派工证据")
        private String executionSnapshot;

        @Schema(description = "来源/唯一末序身份稳定指纹，普通新增报工不变化")
        private String sourceFingerprint;

        @Schema(description = "逐报工事件与本来源完成量完整守恒，旧契约缺字段不能补true")
        private Boolean productionEventsComplete;

        @Schema(description = "本来源完整当前有效末序事件，主键和数量精确文本")
        private List<ProductionEvent> productionEvents;

        @Schema(description = "类型化产出身份/模式/单位/份额缺口，不以空列表冒充完备")
        private List<String> productionEventGaps;
        @Schema(description = "MES完整查询的开始ISO时刻，冻结为每来源补录判定边界")
        private String productionSnapshotStartedAt;
        @Schema(description = "MES完整事件采集结束ISO时刻")
        private String productionObservedAt;

    }

    /** PLAN边界自己的事件类型，避免PLAN API反向依赖MES API。 */
    @Data
    @Accessors(chain = true)
    public static class ProductionEvent {
        @Schema(description = "原末序报工ID文本")
        private String reportId;
        @Schema(description = "物理工单ID文本")
        private String taskId;
        @Schema(description = "来源APS分配ID文本，直接来源为空")
        private String allocationId;
        @Schema(description = "本来源当前有效贡献，最多8位小数")
        private String quantity;
        @Schema(description = "原报工当前物理有效量，仅对照，不跨合批来源累加")
        private String physicalEffectiveQuantity;
        @Schema(description = "原报工冻结模式")
        private String reportMode;
        @Schema(description = "原报工冻结单位")
        private String reportUnit;
        @Schema(description = "原报工记录时刻ISO文本，保留亚秒")
        private String reportTime;
        @Schema(description = "设备事件原时间ISO文本")
        private String sourceEventTime;
        @Schema(description = "设备事件原编号")
        private String sourceEventId;
        @Schema(description = "弱网客户端未独立核实的原采集时间")
        private String offlineCapturedAt;
        @Schema(description = "DEVICE_EVENT/REPORT_REGISTERED/OFFLINE_UNVERIFIED/UNVERIFIED")
        private String timeBasis;
        @Schema(description = "时间依据缺口或仅登记时间的边界")
        private String timeGap;
        @Schema(description = "原报工完整当前事实指纹")
        private String factFingerprint;
        @Schema(description = "来源及唯一末序身份指纹")
        private String sourceFingerprint;
        @Schema(description = "有效来源核定版本ID，独占来源为空")
        private String assignmentId;
        @Schema(description = "核定版本/份额/来源依据的稳定指纹")
        private String provenanceFingerprint;

    }
}
