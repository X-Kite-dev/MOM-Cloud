package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnMpsRemainingReplanLinkEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 业务端剩余工序重排契约，不使用模型、不重算或改写既有生产数量。 */
public final class PlnMpsRemainingReplanDTO {
    private PlnMpsRemainingReplanDTO() {
    }

    @Data
    public static class Assess {
        @Schema(description = "已独立核验的MPS观察主键")
        private Long observationId;
        @Schema(description = "观察内精确时间桶行主键")
        private Long lineId;
        @Schema(description = "永久业务请求号，最多100字")
        private String requestId;
        @Schema(description = "业务预览完整证据SHA256")
        private String expectedFingerprint;
        @Schema(description = "选定直接偏差工序，1至1000个不重复编码")
        private List<String> processCodes;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "原冻结路线节点选择；旧主工序选择仅可唯一对应原工序")
        private List<String> sourceRouteNodeCodes;
        @Schema(description = "APS开工偏差容差分钟，0至1440；不改变MPS原承诺日")
        private Integer toleranceMinutes;
        @Schema(description = "人工评估原因，最多500字")
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Preview {
        @Schema(description = "已核验观察主键文本")
        private String observationId;
        @Schema(description = "原MPS桶行主键文本")
        private String lineId;
        @Schema(description = "实际工厂编码")
        private String factoryCode;
        @Schema(description = "唯一根生产计划主键文本")
        private String issueId;
        @Schema(description = "唯一根生产计划编号")
        private String issueNo;
        @Schema(description = "既有正式排程来源情景主键文本，旧单方案为空")
        private String sourceScenarioId;
        @Schema(description = "原桶兑现证据，归属未知仍为null")
        private PlnMpsExecutionDTO.Bucket bucket;
        @Schema(description = "完整逐工序实际状态与确定性处置，不截断为重点30条")
        private List<Operation> operations;
        @Schema(description = "可选择的直接未开工偏差工序编码，不含已接收或已执行事实")
        private List<String> eligibleProcessCodes;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private List<String> eligibleSourceRouteNodeCodes;
        @Schema(description = "来源/数量/时刻/正式排程或策略待核原因")
        private List<String> gaps;
        @Schema(description = "完整业务证据指纹，发起时重采核对")
        private String fingerprint;
        @Schema(description = "当前采集ISO时刻，不参与稳定指纹")
        private String observedAt;
        @Schema(description = "工序开工偏差容差分钟")
        private Integer toleranceMinutes;
        @Schema(description = "当前是否可发起剩余工序候选评估，不是发布授权")
        private Boolean eligible;
        @Schema(description = "完整根计划PLAN/MES原始取证快照")
        private String executionSnapshot;
    }

    @Data
    @Accessors(chain = true)
    public static class Operation {
        @Schema(description = "正式APS工序主键文本")
        private String operationId;
        @Schema(description = "工序编码")
        private String processCode;
        @Schema(description = "工序名称")
        private String processName;
        @Schema(description = "工序序号")
        private Integer operationSeq;
        @Schema(description = "冻结前置工序集合")
        private String predecessorCodes;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String sourceRouteNodeCode;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String predecessorNodeCodes;
        @Schema(description = "原工序计划量，不改变、不跨工序累加")
        private String plannedQuantity;
        @Schema(description = "原正式计划开工ISO时刻")
        private String plannedStart;
        @Schema(description = "原正式计划完工ISO时刻")
        private String plannedEnd;
        @Schema(description = "MES工单编号")
        private String taskCode;
        @Schema(description = "MES工序状态")
        private String mesStatus;
        @Schema(description = "当前完成量文本，未知保留null")
        private String completedQuantity;
        @Schema(description = "真实开工ISO时刻")
        private String actualStart;
        @Schema(description = "真实完工ISO时刻")
        private String actualFinish;
        @Schema(description = "LOCAL_REPLAN_CANDIDATE/EXECUTION_LOCKED_REVIEW/EXECUTION_BLOCKED/DATA_REPAIR_REQUIRED/OBSERVE")
        private String disposition;
        @Schema(description = "确定性处置原因")
        private String reason;
        @Schema(description = "可作为直接偏差目标，后序闭包仍须发布前重新核验")
        private Boolean eligible;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        @Schema(description = "不可变MPS来源绑定")
        private PlnMpsRemainingReplanLinkEntity link;
        @Schema(description = "当前权威局部情景、三候选、差异及操作历史，不复制缓存状态")
        private PlanResourceLocalReplanDTO.Scenario scenario;
    }
}
