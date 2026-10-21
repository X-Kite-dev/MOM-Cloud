package com.kitex.mom.mes.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PLAN来源计划到MES工单的只读范围契约，不承担数量归属或交付完成判断。
 */
public final class MesPlanTaskScopeDTO {

    private MesPlanTaskScopeDTO() {
    }

    /**
     * 请求仅接受当前实际工厂和已由PLAN核验的计划编号。
     */
    @Data
    @Accessors(chain = true)
    public static class Query {
        /**
         * 当前身份实际工厂。
         */
        @NotBlank
        private String factoryCode;
        /**
         * 精确来源计划编号；不支持模糊搜索或无条件扫描。
         */
        @NotEmpty
        @Size(max = 100)
        private List<@NotBlank @Size(max = 100) String> planNos;
    }

    /**
     * 去重工单与全部命中来源映射；截断不得解释为全量。
     */
    @Data
    @Accessors(chain = true)
    public static class Evidence {
        /**
         * 实际工厂。
         */
        private String factoryCode;
        /**
         * 当前范围命中的全部工单数。
         */
        private Long matchedTaskCount;
        /**
         * 返回的去重工单范围。
         */
        private List<Task> tasks;
        /**
         * 直接来源或APS合批分配来源映射。
         */
        private List<Link> links;
        /**
         * 不可推断的事实或异常来源。
         */
        private List<String> dataGaps;
        /**
         * 是否超过200条工单返回边界。
         */
        private Boolean truncated;
    }

    /**
     * 工单标识仅作为后续受控调查输入，不含可跨产品求和的数量。
     */
    @Data
    @Accessors(chain = true)
    public static class Task {
        /**
         * 工单主键，不经过浮点数转换。
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
         * 工单当前状态。
         */
        private String status;
        /**
         * MES同一只读快照的风险观察时间。
         */
        private LocalDateTime riskObservedAt;
        /**
         * 正式计划截止及实际完工时刻；用于核清已发生延期，不代表订单交付完成。
         */
        private LocalDateTime planEndTime;
        /**
         * 缺失时不能把FINISH/CLOSE状态当作历史延期已兑现证明。
         */
        private LocalDateTime actualFinishTime;
        /**
         * 工单是否逾期；计划截止、状态或完工时间不足时为null，不填false。
         */
        private Boolean delayed;
        /**
         * 当前工厂、租户的暂停工序数；无工序时为null，不填零。
         */
        private Long pausedOperationCount;
        /**
         * 工单不合格数量；未维护或非法负数时为null，不跨工单求和。
         */
        private BigDecimal unqualifiedQuantity;
        /**
         * 风险判定所缺事实，与订单来源关联缺口分别表达。
         */
        private List<String> riskDataGaps;
    }

    /**
     * 一个工单可通过合批分配关联多个来源计划。
     */
    @Data
    @Accessors(chain = true)
    public static class Link {
        /**
         * 来源物料与精确分配账；不得把整张合批工单量归属单个订单。
         */
        private String materialCode;
        /**
         * DIRECT_EXCLUSIVE、ALLOCATED或DATA_GAP。
         */
        private String quantityAttributionStatus;
        /**
         * 来源分配账主键；直接来源为null。
         */
        private Long allocationId;
        /**
         * 来源计划对应的正式分配量。
         */
        private BigDecimal allocatedQuantity;
        /**
         * 已由MES源数量账分配到该计划的累计完工量。
         */
        private BigDecimal completedQuantity;
        /**
         * 已由MES分配账归属该计划的累计入库量，不代表客户交付。
         */
        private BigDecimal inStockQuantity;
        /**
         * 数量来源不可用时禁止按零解释。
         */
        private List<String> quantityDataGaps;
        /**
         * 精确来源计划编号。
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
