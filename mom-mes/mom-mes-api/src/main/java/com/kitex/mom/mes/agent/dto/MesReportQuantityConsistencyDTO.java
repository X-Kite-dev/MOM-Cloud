package com.kitex.mom.mes.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;
import java.util.List;

/** 原工单报工、质量可用量和MES入库申请来源的只读核对，不代表WMS实收。 */
public final class MesReportQuantityConsistencyDTO {
    private MesReportQuantityConsistencyDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        @Schema(description = "取证协议版本")
        private String version;
        @Schema(description = "实际工厂编码")
        private String factoryCode;
        @Schema(description = "原MES工单主键，字符串传输避免前端精度丢失")
        private String taskId;
        @Schema(description = "原MES工单编码")
        private String taskCode;
        @Schema(description = "CONSISTENT/ANOMALY/DATA_GAP；未知不会填为正常")
        private String status;
        @Schema(description = "该独立重复读证据的完成时刻；与外层统计观察不是跨事务原子快照")
        private LocalDateTime observedAt;
        @Schema(description = "所有检查均可核定；存在已证实异常时仍可为true，不代表正常")
        private Boolean scopeComplete;
        @Schema(description = "完整核验范围中的报工条数")
        private Integer reportCount;
        @Schema(description = "原分配扣除已入账红冲后的正净来源条数")
        private Integer allocationCount;
        @Schema(description = "完整范围检查结论，独立于展示明细上限")
        private List<Check> checks;
        @Schema(description = "优先展示异常和缺证报工，最多50条；检查本身使用完整受限范围")
        private List<Report> reports;
        @Schema(description = "原入库申请的报工净来源，最多50条")
        private List<Allocation> allocations;
        @Schema(description = "展示明细是否受50条上限限制，不表示完整检查被截断")
        private Boolean detailTruncated;
        @Schema(description = "原末序及计划来源核定指纹")
        private String sourceFingerprint;
        @Schema(description = "无法核定的范围或来源缺口")
        private List<String> dataGaps;
        @Schema(description = "原业务证据引用")
        private List<String> evidenceRefs;
    }

    @Data
    @Accessors(chain = true)
    public static class Check {
        @Schema(description = "检查编码")
        private String code;
        @Schema(description = "检查名称")
        private String name;
        @Schema(description = "PASS/ANOMALY/UNKNOWN")
        private String state;
        @Schema(description = "检查结论说明")
        private String summary;
    }

    @Data
    @Accessors(chain = true)
    public static class Report {
        @Schema(description = "原报工主键")
        private String reportId;
        @Schema(description = "原报工永久请求号")
        private String requestId;
        @Schema(description = "原工序任务编码")
        private String produceTaskCode;
        @Schema(description = "原报工冻结模式")
        private String reportMode;
        @Schema(description = "原报工冻结单位")
        private String unit;
        @Schema(description = "原报工状态，EFFECTIVE或REVERSED；历史缺值保持未知")
        private String status;
        @Schema(description = "原执行性质，ROUTE_RETURN不重复计产出")
        private String executionKind;
        @Schema(description = "原始报工量，十进制字符串；未知为null")
        private String originalQuantity;
        @Schema(description = "累计纠正量，十进制字符串；未知为null")
        private String correctedQuantity;
        @Schema(description = "当前有效量，已冲销为零；不等同工单成品量")
        private String effectiveQuantity;
        @Schema(description = "当前可用于入库的质量可用量；未知为null")
        private String qualityAvailableQuantity;
        @Schema(description = "原报工质量数量状态")
        private String qualityQuantityStatus;
        @Schema(description = "当前已占用入库净来源量；归属未核清为null")
        private String netInboundAllocatedQuantity;
        @Schema(description = "本条报工的数量异常或缺证说明")
        private List<String> issues;
    }

    @Data
    @Accessors(chain = true)
    public static class Allocation {
        @Schema(description = "原入库报工来源明细主键")
        private String sourceDetailId;
        @Schema(description = "原MES入库申请回执主键")
        private String receiptId;
        @Schema(description = "原报工主键")
        private String reportId;
        @Schema(description = "原报工永久请求号")
        private String requestId;
        @Schema(description = "原工序任务编码")
        private String produceTaskCode;
        @Schema(description = "原分配扣除已入账红冲后的净额，不是WMS实收数量")
        private String netQuantity;
    }
}
