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

        /** 执行回评选中的 APS 候选任务主键，仅取该正式候选出生的工单。 */
        @Schema(description = "精确 APS 候选任务 ID，留空时沿用来源计划查询")
        private Long schedulingTaskId;
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

        /** 按候选查询时逐物理工单返回。同一合批工单只出现一次。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "候选批次实际工单和冻结工序")
        private List<BatchOrder> batchOrders;

        /** 实际查询的候选身份，即使尚未接单也必须回显。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "实际查询的 APS 候选任务主键文本")
        private String schedulingTaskId;
    }

    /**
     * @param batchId MES 建单时记录的 APS 批次主键文本
     * @param batchNo 首次接单的执行批号
     * @param requestId 原计划下达的永久请求号
     * @param taskId MES 工单主键文本
     * @param taskCode MES 工单编码
     * @param planNo 本批以其建单的主生产计划号
     * @param productCode 工单物料编码
     * @param quantity 物料基本单位的工单应产量，十进制文本
     * @param reportFingerprint 末序有效报工及来源核定快照指纹，用于区分数量相同的不同原始记录
     * @param operations 同一工单实际工序和可移动状态，与来源份额取自同一数据库快照
     */
    @Schema(description = "已发布候选下的一张物理工单")
    public record BatchOrder(String batchId, String batchNo, String requestId, String taskId, String taskCode,
                             String planNo, String productCode, String quantity, String reportFingerprint,
                             List<MesApsLocalScheduleDTO.OperationState> operations) {
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
        @Schema(description = "回评使用的报工时刻 ISO 文本；带来源事件的集成报工取 MES 实际建账时刻")
        private String reportTime;
        @Schema(description = "设备或集成来源事件原时间 ISO 文本，保持原精度")
        private String sourceEventTime;
        @Schema(description = "设备或集成来源事件原号")
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
