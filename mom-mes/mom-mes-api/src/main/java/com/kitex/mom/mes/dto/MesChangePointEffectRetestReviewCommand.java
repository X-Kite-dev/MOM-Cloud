package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 独立人员只核验口径和证据，不得手工将计算失败改为通过。
 */
@Data
public class MesChangePointEffectRetestReviewCommand {
    @Schema(description = "复测主键")
    private Long id;

    @Schema(description = "永久复核请求号，最多100字")
    private String requestId;

    @Schema(description = "APPROVE确认计算结论或REJECT退回重建")
    private String decision;

    @Schema(description = "同口径、生产样本、原CAPA目标和现场证据核验说明，最多2000字")
    private String remark;

    @Schema(description = "SCOPE_APPLICABLE/SAMPLES_COMPARABLE/CRITERIA_ALIGNED/EVIDENCE_CHECKED")
    private List<String> checks;
}
