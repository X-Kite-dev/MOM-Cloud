package com.kitex.mom.mes.agent.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产全量统计的独立线协议；不改变生产进度和固定调查的旧合同。
 */
public final class MesProductionStatisticsDTO {
    private MesProductionStatisticsDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产统计受控范围，编码必须明确，日期结束不含")
    public static class Query {
        @Schema(description = "当前登录上下文的授权实际工厂编码，不支持跨工厂")
        @NotBlank
        private String factoryCode;
        @Schema(description = "是否完整历史范围；两窗比较不允许完整历史")
        private Boolean allTime = false;
        @Schema(description = "本窗开始时间，包含；完整历史时省略")
        private LocalDateTime startTime;
        @Schema(description = "本窗结束时间，不包含；完整历史时省略")
        private LocalDateTime endTime;
        @Schema(description = "明确生产工单编码，指定工单仍应用日期窗口")
        private String taskCode;
        @Schema(description = "工单车间；报工取原派工车间归属，不用当前主数据补造")
        private String workshopCode;
        @Schema(description = "原派工冻结班次编码，不按当前员工排班推算")
        private String shiftCode;
        @Schema(description = "明确物料编码，不按名称或当前主数据推算")
        private String materialCode;
        @Schema(description = "报工时冻结单位，仅筛选报工事件；数量排名必须同时指定物料和单位")
        private String reportUnit;
        @Schema(description = "PREVIOUS_PERIOD、PREVIOUS_YEAR或EXPLICIT")
        private String comparisonKind;
        @Schema(description = "对比窗开始时间，包含")
        private LocalDateTime comparisonStart;
        @Schema(description = "对比窗结束时间，不包含")
        private LocalDateTime comparisonEnd;
        @Schema(description = "WORKSHOP、SHIFT或TASK；无产线和班组冻结实绩")
        private String rankGroupBy;
        @Schema(description = "TASK_COUNT、REPORT_COUNT、REPORT_QUANTITY或QUALITY_REJECTED_QUANTITY")
        private String rankMetric;
        @Schema(description = "ASC从少到多或DESC从多到少，同值按编码稳定排序")
        private String rankDirection;
        @Schema(description = "全命中聚合后限返1至50组，不限制聚合总体")
        private Integer rankLimit;

        /**
         * 即使应用默认兼容未知JSON字段，也不得静默忽略产线、班组或自由查询条件。
         */
        @JsonAnySetter
        public void rejectUnknownCondition(String field, Object value) {
            throw new IllegalArgumentException("生产统计不支持条件：" + field);
        }
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "全命中聚合证据，状态为采集时点，实绩为报工事件时间")
    public static class Evidence {
        @Schema(description = "固定来源合同MES_PRODUCTION_STATISTICS_QUERY@1")
        private String toolCode;
        @Schema(description = "实际应用的授权工厂编码")
        private String factoryCode;
        @Schema(description = "逐字段回显实际应用的冻结筛选、时间和排名范围")
        private Query scope;
        @Schema(description = "计数、归属、单位、当前状态与实绩时间的完整来源口径")
        private String metricDefinition;
        @Schema(description = "同一只读事务的来源采集时点")
        private LocalDateTime observedAt;
        @Schema(description = "本窗全命中统计")
        private WindowStatistics summary;
        @Schema(description = "请求比较时的对比窗全命中统计")
        private WindowStatistics comparison;
        @Schema(description = "来源计算的差值与变化率，不在浏览器重算")
        private List<Delta> deltas = new ArrayList<>();
        @Schema(description = "全命中先聚合再排序后的来源原序列")
        private List<Ranking> ranking = new ArrayList<>();
        @Schema(description = "排序限返之前的全部命中分组数")
        private Long rankingPopulationCount;
        @Schema(description = "固定MATCHED，禁止RETURNED_DETAILS伪装全量")
        private String rankingMetricScope;
        @Schema(description = "缺少归属、状态、单位或数量等明确缺口")
        private List<String> dataGaps = new ArrayList<>();
        @Schema(description = "来源数据集证据引用")
        private List<String> evidenceRefs = new ArrayList<>();
        @Schema(description = "固定false：统计不由限量明细近似；排名限返另由rankLimit表达")
        private Boolean truncated = false;
    }

    @Data
    @Accessors(chain = true)
    public static class WindowStatistics {
        @Schema(description = "实际统计窗口开始，包含；完整历史为空")
        private LocalDateTime rangeStart;
        @Schema(description = "实际统计窗口结束，不包含；完整历史为空")
        private LocalDateTime rangeEnd;
        @Schema(description = "计划或实际执行区间命中的全量工单数")
        private Long matchedTaskCount;
        @Schema(description = "命中工单采集时点未开始数，不是历史状态")
        private Long currentNotStartedTaskCount;
        @Schema(description = "命中工单采集时点生产中数")
        private Long currentInProgressTaskCount;
        @Schema(description = "命中工单采集时点完成或关闭数")
        private Long currentFinishedTaskCount;
        @Schema(description = "命中工单当前存在暂停工序的数量，可与状态计数重叠")
        private Long currentPausedTaskCount;
        @Schema(description = "命中工单当前超计划结束或实际延期完成数")
        private Long currentDelayedTaskCount;
        @Schema(description = "report_time窗口内全量有效报工事件数")
        private Long effectiveReportCount;
        @Schema(description = "report_time窗口内已冲销报工事件数，不纳入有效数量")
        private Long reversedReportCount;
        @Schema(description = "窗口内有效报工涉及的去重工单数")
        private Long distinctReportedTaskCount;
        @Schema(description = "满足基础时间及对象范围、但缺少原派工归属的报工数；无法归属车间或班次")
        private Long unattributedReportCount;
        @Schema(description = "按物料及报工冻结单位独立聚合，不跨组相加")
        private List<QuantitySummary> quantitySummaries = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class QuantitySummary {
        @Schema(description = "报工冻结物料编码，缺失标记UNKNOWN")
        private String materialCode;
        @Schema(description = "报工冻结单位，缺失标记UNKNOWN")
        private String reportUnit;
        @Schema(description = "有效报工量，纠正后数量优先；任一缺量时保持空")
        private BigDecimal reportedQuantity;
        @Schema(description = "当前同步的质量已放行量，缺量保持空")
        private BigDecimal qualityReleasedQuantity;
        @Schema(description = "当前同步的质量待放行量，缺量保持空")
        private BigDecimal qualityPendingQuantity;
        @Schema(description = "当前同步的质量拒绝量，缺量保持空")
        private BigDecimal qualityRejectedQuantity;
        @Schema(description = "本组存在未知或负数量的有效事件数，不把缺数当零")
        private Long incompleteQuantityCount;
    }

    @Data
    @Accessors(chain = true)
    public static class Delta {
        @Schema(description = "固定指标键：工单数、有效报工数、去重工单数、报工量或质量拒绝量")
        private String metric;
        @Schema(description = "数量指标所属物料，计数指标为空")
        private String materialCode;
        @Schema(description = "数量指标所属冻结单位，计数指标为空")
        private String reportUnit;
        @Schema(description = "来源本窗原值，未知为空")
        private BigDecimal currentValue;
        @Schema(description = "来源对比窗原值，无事件为零、未知为空")
        private BigDecimal previousValue;
        @Schema(description = "来源本窗减对比窗的差值，缺数为空")
        private BigDecimal absoluteChange;
        @Schema(description = "(本窗-对比窗)/对比窗*100；基数为0或缺数时保持空")
        private BigDecimal percentChange;
        @Schema(description = "COMPARABLE可比较、ZERO_BASELINE零基数或MISSING_DATA缺数")
        private String status;
    }

    @Data
    @Accessors(chain = true)
    public static class Ranking {
        @Schema(description = "来源稳定排序序号，不表示并列名次")
        private Integer rank;
        @Schema(description = "原冻结车间、班次或工单编码，缺失标记UNKNOWN")
        private String groupCode;
        @Schema(description = "当前使用来源编码，不用当前主数据补造历史名称")
        private String groupName;
        @Schema(description = "全命中聚合后的指标原值，缺量为空")
        private BigDecimal value;
        @Schema(description = "请求的明确物料编码")
        private String materialCode;
        @Schema(description = "请求的明确报工冻结单位")
        private String reportUnit;
    }
}
