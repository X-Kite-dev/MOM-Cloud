package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * QMS反查已提交的MES独立复核事实；不接受调用方自填结论。
 */
@Data
@Accessors(chain = true)
public class MesChangePointEffectRetestProof {
    @Schema(description = "复测主键")
    private Long retestId;

    @Schema(description = "复测编号")
    private String retestNo;

    @Schema(description = "改善来源主键")
    private Long improvementId;

    @Schema(description = "改善来源编号")
    private String improvementNo;

    @Schema(description = "权威CAPA主键")
    private Long capaId;

    @Schema(description = "冻结关闭周期")
    private Integer closedCycleNo;

    @Schema(description = "关闭状态证据指纹")
    private String capaFingerprint;

    @Schema(description = "复开永久请求号")
    private String reopenRequestId;

    @Schema(description = "经独立复核的复测证据指纹")
    private String evidenceFingerprint;

    @Schema(description = "仅FAIL允许复开")
    private String status;

    @Schema(description = "独立复核人")
    private String reviewedBy;

    @Schema(description = "证据申请人")
    private String collectedBy;

    @Schema(description = "新周期期限天数")
    private Integer reopenDueDays;

    @Schema(description = "复测核验说明")
    private String reviewRemark;

    @Schema(description = "实际工厂")
    private String factoryCode;

    @Schema(description = "实际租户")
    private Long tenantId;
}
