package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesReportReverseOperationEntity;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报工受控冲销命令、事实证据与履历。
 */
public final class MesReportReverseRequestDTO {
    private MesReportReverseRequestDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class SubmitCommand {
        private Long reportId;
        @Schema(description = "SINGLE或IDENTIFIER_CHAIN；为空时采用预览推荐范围")
        private String correctionMode;
        @Schema(maxLength = 64)
        private String requestId;
        @Schema(maxLength = 64)
        private String expectedEvidenceFingerprint;
        @Schema(maxLength = 500)
        private String reason;
        @Schema(description = "部分数量纠正时的冲减数量；完整冲销不填")
        private BigDecimal correctionQuantity;
        @Schema(description = "批准后是否生成可分次使用的受控重报许可")
        private Boolean createReReportPermit;
    }

    @Data
    @Accessors(chain = true)
    public static class ReviewCommand {
        private Long requestId;
        @Schema(maxLength = 64)
        private String operationRequestId;
        @Schema(maxLength = 64)
        private String expectedEvidenceFingerprint;
        private Boolean approved;
        @Schema(maxLength = 500)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class WithdrawCommand {
        private Long requestId;
        @Schema(maxLength = 64)
        private String operationRequestId;
        @Schema(maxLength = 500)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private MesTaskReportEntity report;
        @Schema(description = "服务端推荐且本次指纹对应的冲销范围")
        private String correctionMode;
        @Schema(description = "按实际撤回顺序排列的冻结报工")
        private List<ChainReport> chainReports;
        private String evidenceFingerprint;
        private Boolean eligible;
        private List<String> blockers;
        private MesReportReverseRequestEntity activeRequest;
        private BigDecimal effectiveQuantity;
        private BigDecimal maxPartialCorrectionQuantity;
        private Boolean partialCorrectionSupported;
        private String partialCorrectionMessage;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private MesReportReverseRequestEntity request;
        @Schema(description = "原申请证据协议，不取当前预览的版本")
        private String evidenceVersion;
        @Schema(description = "原节点申请正文中的冻结范围；历史协议为空，不回填当前节点")
        private List<NodeScope> frozenNodeScopes;
        private Evidence currentEvidence;
        private List<MesReportReverseOperationEntity> operations;
        private List<com.kitex.mom.mes.entity.MesReportReverseScopeEntity> scopes;
    }

    /**
     * 链式影响预览中的最小可核对报工事实。
     */
    @Data
    @Accessors(chain = true)
    public static class ChainReport {
        private Long reportId;
        private String arrangeCode;
        private String produceTaskCode;
        @Schema(description = "本次取证的实际执行协议；空表示历史合同")
        private String routeExecutionSchemaVersion;
        @Schema(description = "本次取证的原实际路线节点，不表示历史申请已冻结此字段")
        private String sourceRouteNodeCode;
        private String routeCode;
        private String routeName;
        private Integer routeOrder;
        private BigDecimal reportNum;
        private LocalDateTime reportTime;
    }

    /** 原申请正文中的只读节点范围，长整型ID以文本传输。 */
    @Data
    @Accessors(chain = true)
    public static class NodeScope {
        @Schema(description = "原报告ID文本")
        private String reportId;
        @Schema(description = "原派工ID文本")
        private String arrangeId;
        @Schema(description = "原派工编码")
        private String arrangeCode;
        @Schema(description = "原实际工序任务编码")
        private String produceTaskCode;
        @Schema(description = "主工序编码，不作为节点身份")
        private String routeCode;
        @Schema(description = "原冻结路线节点编码")
        private String sourceRouteNodeCode;
    }
}
