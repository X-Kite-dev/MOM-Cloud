package com.kitex.mom.oms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售订单生产一致性调查范围；仅提供来源证据，不把工单完工量等同于订单交付量。
 */
public final class OmsProductionScopeDTO {

    private OmsProductionScopeDTO() {
    }

    /**
     * 精确订单范围；项目入口需先通过显式人工审核关联解析订单，不从产品或客户猜测。
     */
    @Data
    @Accessors(chain = true)
    public static class Query {
        /**
         * 当前身份工厂。
         */
        @NotBlank
        private String factoryCode;
        /**
         * 销售订单编码。
         */
        @NotBlank
        @Size(max = 100)
        private String orderCode;
    }

    /**
     * 冻结OMS、PLAN来源及MES范围的跨域证据。
     */
    @Data
    @Accessors(chain = true)
    public static class Evidence {
        /**
         * 指标和数量归属限制。
         */
        private String metricDefinition;
        /**
         * 实际工厂。
         */
        private String factoryCode;
        /**
         * 销售订单编号。
         */
        private String orderCode;
        /**
         * 观察时间；跨域HTTP事实并非数据库全局同一快照。
         */
        private LocalDateTime observedAt;
        /**
         * 当前订单全部交付行数。
         */
        private Long matchedLineCount;
        /**
         * 每条已返回交付行的来源核验状态。
         */
        private List<Line> lines;
        /**
         * MES去重工单范围，最多200条。
         */
        private List<Task> tasks;
        /**
         * 需求到生产计划再到工单的核验关联。
         */
        private List<Link> links;
        /**
         * 全部命中来源工单数；MES不可用时为null而非0。
         */
        private Long matchedTaskCount;
        /**
         * 整单来源链是否完整；不代表所有一致性维度调查完成。
         */
        private Boolean scopeComplete;
        /**
         * 任何域的返回范围截断标记。
         */
        private Boolean truncated;
        /**
         * 来源和数量归属缺口。
         */
        private List<String> dataGaps;
        /**
         * 可追溯原始表证据。
         */
        private List<String> evidenceRefs;
    }

    /**
     * OMS证据中的工单只读投影，PLAN API不反向依赖MES API。
     */
    @Data
    @Accessors(chain = true)
    public static class Task {
        /**
         * 工单精确主键。
         */
        private Long taskId;
        /**
         * 工单编码。
         */
        private String taskCode;
        /**
         * 物料编码。
         */
        private String matCode;
        /**
         * 工单状态。
         */
        private String status;
        /**
         * MES风险事实的观察时间；跨域观察不是全局同一时刻快照。
         */
        private LocalDateTime riskObservedAt;
        /**
         * MES正式计划截止，仅用于本工单偏差核查。
         */
        private LocalDateTime planEndTime;
        /**
         * MES实际完工时刻，工单完工不等于订单交付。
         */
        private LocalDateTime actualFinishTime;
        /**
         * MES确定性逾期判断；null表示判定事实不足。
         */
        private Boolean delayed;
        /**
         * 暂停工序数；null表示未取得有效工序事实。
         */
        private Long pausedOperationCount;
        /**
         * 本工单不合格数量，仅用于本工单判险，不归属整单交付量。
         */
        private BigDecimal unqualifiedQuantity;
        /**
         * MES风险判定缺口。
         */
        private List<String> riskDataGaps;
    }

    /**
     * 交付行单独核验，重复/不一致需求不可用于推断工单归属。
     */
    @Data
    @Accessors(chain = true)
    public static class Line {
        /**
         * OMS正式交付行的原单位，禁止跨单位聚合。
         */
        private String productUnit;
        /**
         * 本交付行正式需求量。
         */
        private BigDecimal orderedQuantity;
        /**
         * 精确成品来源计划的已归属完工量；缺数为null，非客户交付量。
         */
        private BigDecimal attributedCompletedQuantity;
        /**
         * 精确成品来源计划的已归属入库量；缺数为null，非净可交量。
         */
        private BigDecimal attributedInStockQuantity;
        /**
         * COMPLETE、NOT_STARTED、DATA_GAP；只评价该行成品生产归属。
         */
        private String quantityAttributionStatus;
        /**
         * 该行生产数量归属缺口，不将零件和其他订单工单量合计。
         */
        private List<String> quantityDataGaps;
        /**
         * 交付行主键。
         */
        private Long lineId;
        /**
         * 产品编码。
         */
        private String productCode;
        /**
         * 核验通过的来源需求编号。
         */
        private String demandNo;
        /**
         * AVAILABLE、NOT_STARTED、DATA_GAP。
         */
        private String sourceStatus;
        /**
         * 当前行核验计划编号。
         */
        private List<String> planNos;
    }

    /**
     * 多对多来源映射；合批工单不得重复计数或直接归属全部数量。
     */
    @Data
    @Accessors(chain = true)
    public static class Link {
        /**
         * MES直接来源或不可变合批分配账的数量归属。
         */
        private String materialCode;
        /**
         * 合批/拆批的精确分配账主键；专属直连工单为空。
         */
        private Long allocationId;
        /**
         * 该分配账计划归属数量，不能采用工单全量覆盖多条订单。
         */
        private BigDecimal allocatedQuantity;
        /**
         * 经同工单全部分配守恒核对的该账完成量。
         */
        private BigDecimal completedQuantity;
        /**
         * 经同工单全部分配守恒核对的该账成品入库量。
         */
        private BigDecimal inStockQuantity;
        /**
         * DIRECT_EXCLUSIVE、ALLOCATED或DATA_GAP。
         */
        private String quantityAttributionStatus;
        /**
         * 明确不可归属的缺口，禁止用0替代未知。
         */
        private List<String> quantityDataGaps;
        /**
         * 订单交付行。
         */
        private Long lineId;
        /**
         * 来源需求编号。
         */
        private String demandNo;
        /**
         * 生产计划编号。
         */
        private String planNo;
        /**
         * 去重工单编码。
         */
        private String taskCode;
        /**
         * DIRECT_PLAN或APS_BATCH_ALLOCATION。
         */
        private String relationType;
    }
}
