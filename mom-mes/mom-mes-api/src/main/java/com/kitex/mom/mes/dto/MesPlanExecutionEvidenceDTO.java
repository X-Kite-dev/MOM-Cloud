package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 计划兑现业务查询契约；精确来源数量和实际工序状态，不依赖Agent或LLM。
 */
public final class MesPlanExecutionEvidenceDTO {
    private MesPlanExecutionEvidenceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @Schema(description = "当前身份实际工厂")
        private String factoryCode;
        @Schema(description = "PLAN已核验的精确根生产计划编号，最多500个")
        private List<String> planNos;
        @Schema(description = "显式请求完整逐报工来源事件；不请求时不把空列表冒充完整事件")
        private Boolean includeProductionEvents;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        @Schema(description = "实际租户")
        private Long tenantId;
        @Schema(description = "实际工厂")
        private String factoryCode;
        @Schema(description = "同一MES只读快照的采集时刻，ISO本地时间文本保留亚秒精度")
        private String observedAt;
        @Schema(description = "全部被请求计划的证据，一条计划一个元素，不截断")
        private List<Plan> plans;
    }

    @Data
    @Accessors(chain = true)
    public static class Plan {
        @Schema(description = "精确来源计划号")
        private String planNo;
        @Schema(description = "直接工单或APS分配来源，不能用合批总量代替桶归属量")
        private List<Link> links;
        @Schema(description = "来源谱系、数量、日期或工序缺口")
        private List<String> gaps;
    }

    @Data
    @Accessors(chain = true)
    public static class Link {
        @Schema(description = "MES工单主键")
        private Long taskId;
        @Schema(description = "MES工单编号")
        private String taskCode;
        @Schema(description = "产品编码")
        private String productCode;
        @Schema(description = "DIRECT_PLAN或APS_BATCH_ALLOCATION")
        private String relationType;
        @Schema(description = "APS分配行主键，直接来源为空")
        private Long allocationId;
        @Schema(description = "APS分配账的根生产计划主键，直接来源为空")
        private Long sourceIssueId;
        @Schema(description = "该来源精确分配/直接计划量，十进制文本")
        private String sourceQuantity;
        @Schema(description = "该来源完成报工量，非入库或质量净放行量，未知保留null")
        private String completedQuantity;
        @Schema(description = "归属已入库数量，未知保留null，不能与报工完成混用")
        private String inStockQuantity;
        @Schema(description = "物理工单唯一末序当前有效报工合计，非完工结算量；只展示，不按来源复制累加")
        private String physicalCompletedQuantity;
        @Schema(description = "物理工单完工结算字段，与唯一末序在制有效报工量分开")
        private String settledCompletedQuantity;
        @Schema(description = "DIRECT/EXPLICIT/UNVERIFIED，来源计算依据；物理满量不证明实际来源归属")
        private String quantityBasis;
        @Schema(description = "工单当前状态")
        private String status;
        @Schema(description = "工单/工序实际最早开工ISO时刻，保留亚秒")
        private String actualStartTime;
        @Schema(description = "真实工单完工ISO时刻，不能用状态猜测")
        private String actualFinishTime;
        @Schema(description = "已进入执行事实；任何工序实际开始、数量或执行状态都保持保护")
        private Boolean started;
        @Schema(description = "工序数")
        private Integer operationCount;
        @Schema(description = "当前暂停工序数")
        private Long pausedOperationCount;
        @Schema(description = "该工单全部工序仍未接收、开工且允许评估移动，不代表发布授权")
        private Boolean allOperationsMovable;
        @Schema(description = "工序、派工、实际日期/数量及状态的完整原始快照")
        private String executionSnapshot;
        @Schema(description = "当前来源及唯一末序身份指纹，不随正常新增报工变化")
        private String sourceFingerprint;
        @Schema(description = "逐份事件来源贡献与completedQuantity完整守恒，未知/未请求保持false或null")
        private Boolean productionEventsComplete;
        @Schema(description = "本精确来源的完整当前有效末序报工事件，不包含其他合批来源或重过站")
        private List<ProductionEvent> productionEvents;
        @Schema(description = "事件身份、模式、单位或数量缺口，与报工登记/设备时效口径分开")
        private List<String> productionEventGaps;
        @Schema(description = "本MES一致性查询开始前的ISO时刻，用于基线补录边界，不使用PLAN响应结束时间")
        private String productionSnapshotStartedAt;
        @Schema(description = "完整MES事件查询采集结束时刻ISO文本")
        private String productionObservedAt;
    }

    /**
     * 数量与主键保持精确文本；时间依据不等于现场实物完成证明。
     */
    @Data
    @Accessors(chain = true)
    public static class ProductionEvent {
        @Schema(description = "原末序报工主键文本")
        private String reportId;
        @Schema(description = "所属物理工单主键文本")
        private String taskId;
        @Schema(description = "精确APS来源分配主键文本；直接来源为空")
        private String allocationId;
        @Schema(description = "当前本来源有效贡献，最多8位小数；不是物理合批总量")
        private String quantity;
        @Schema(description = "原报工当前物理有效量，仅对照不跨来源累加")
        private String physicalEffectiveQuantity;
        @Schema(description = "原报工冻结模式")
        private String reportMode;
        @Schema(description = "原报工冻结单位")
        private String reportUnit;
        @Schema(description = "原报工记录时间ISO文本，设备报工可能等于设备事件时间")
        private String reportTime;
        @Schema(description = "设备事件原时间ISO文本，保持亚秒精度")
        private String sourceEventTime;
        @Schema(description = "设备事件原号")
        private String sourceEventId;
        @Schema(description = "弱网客户端记录时刻，只作为未独立核实的客户端证据")
        private String offlineCapturedAt;
        @Schema(description = "DEVICE_EVENT/REPORT_REGISTERED/OFFLINE_UNVERIFIED/UNVERIFIED")
        private String timeBasis;
        @Schema(description = "按当前时间依据不可形成现场实际完成时效结论的说明")
        private String timeGap;
        @Schema(description = "原报工完整当前事实指纹，纠正/模式/标识/设备时刻变化即变化")
        private String factFingerprint;
        @Schema(description = "原来源及末序身份指纹")
        private String sourceFingerprint;
        @Schema(description = "核定版本主键；独占来源为空")
        private String assignmentId;
        @Schema(description = "核定ID/份额/来源事实的稳定依据指纹，正常其他报工推进不改变")
        private String provenanceFingerprint;
    }
}
