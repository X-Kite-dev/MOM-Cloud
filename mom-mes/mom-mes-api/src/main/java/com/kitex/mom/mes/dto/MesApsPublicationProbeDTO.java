package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * APS跨账恢复只读取证契约：永久回执证明曾应用，当前计划证明仍对应；实际执行推进独立呈现。
 */
public final class MesApsPublicationProbeDTO {
    private MesApsPublicationProbeDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @Schema(description = "从PLAN不可变候选/原方案重建的完整原MES命令，必须逐字匹配已提交回执")
        private MesApsLocalScheduleDTO.ApplyCommand expectedCommand;

        @Schema(description = "原根计划全量工序编码，1至1000个唯一编码；不只核对移动范围")
        private List<String> expectedProcessCodes;
        @Schema(description = "新合同原根计划全量稳定节点，与工序清单逐项对应；null为旧唯一主工序合同")
        private List<String> expectedSourceRouteNodeCodes;
    }

    @Data
    @Accessors(chain = true)
    public static class Scope {
        @Schema(description = "PLAN局部案件主键，正数")
        private Long caseId;

        @Schema(description = "唯一根生产计划号")
        private String planNo;

        @Schema(description = "必须等于认证上下文实际factory-code")
        private String factoryCode;
    }

    @Data
    @Accessors(chain = true)
    public static class ReceiptRef {
        @Schema(description = "原业务发布永久号，不含固定MES子请求后缀")
        private String businessRequestId;

        @Schema(description = "MES原应用永久号")
        private String mesRequestId;

        @Schema(description = "原应用人员实名账号，历史缺身份不得补造")
        private String appliedBy;

        @Schema(description = "原MES应用时刻，不是PLAN补记时刻")
        private LocalDateTime appliedTime;
    }

    @Data
    @Accessors(chain = true)
    public static class CurrentSchedule {
        @Schema(description = "所有目标工序、计划资源、资格快照、派工计划及工单日期仍精确匹配")
        private Boolean matches;

        @Schema(description = "当前完整MES状态及执行指纹；实际接收/开工/报工不改写")
        private MesApsLocalScheduleDTO.StateBundle facts;

        @Schema(description = "精确来源、全量映射、计划属性或工单摘要不一致原因")
        private List<String> gaps;

        @Schema(description = "唯一物理工单的原计划量，多物理工单不能猜合计归属")
        private BigDecimal plannedQuantity;

        @Schema(description = "新节点查询按节点编码、旧查询按主工序编码的当前计划量；仅核对不改量")
        private Map<String, BigDecimal> operationQuantities;
    }

    @Data
    @Accessors(chain = true)
    public static class Reply {
        @Schema(description = "NOT_OBSERVED/APPLIED_MATCHED/APPLIED_DRIFTED；未观察不代表取消")
        private String state;

        @Schema(description = "不可变原应用回执，无记录则为空")
        private MesApsLocalScheduleDTO.ApplyReceipt receipt;

        @Schema(description = "原应用人员账号，续接必须核对原身份及当前工厂权限")
        private String appliedBy;

        @Schema(description = "原接收命令已与不可变PLAN载荷精确匹配")
        private Boolean commandMatched;

        @Schema(description = "原MES应用时刻ISO精确值，用于核对审批窗口，不受通用秒级日期序列化影响")
        private String appliedTimeExact;

        @Schema(description = "当前计划和实际执行独立核验结果")
        private CurrentSchedule current;

        @Schema(description = "服务端观察时刻；不重写原应用时刻")
        private LocalDateTime observedTime;
    }
}
