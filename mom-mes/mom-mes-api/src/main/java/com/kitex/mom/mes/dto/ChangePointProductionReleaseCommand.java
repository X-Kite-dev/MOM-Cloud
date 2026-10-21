package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 变化点恢复生产审批命令。
 *
 * <p>审批只解除当前变化点的逻辑生产门，不代替现场开工，也不会绕过设备、质量、物料等其他门禁。</p>
 */
@Data
@Schema(description = "变化点恢复生产审批命令")
public class ChangePointProductionReleaseCommand {

    @Schema(description = "变化点事件ID")
    private Long id;

    @Schema(description = "调用方永久幂等请求号")
    private String requestId;

    @Schema(description = "审批结论：APPROVE/REJECT")
    private String decision;

    @Schema(description = "审批说明")
    private String remark;

    @Schema(description = "页面读取到的恢复生产证据指纹，用于防止审批期间事实漂移")
    private String expectedEvidenceFingerprint;

    @Schema(description = "已独立核对现场条件")
    private Boolean siteConditionChecked;

    @Schema(description = "已独立核对设备和过程参数")
    private Boolean equipmentParameterChecked;

    @Schema(description = "已独立核对物料和质量条件")
    private Boolean materialQualityChecked;

    @Schema(description = "已独立核对人员资格和作业标准")
    private Boolean personnelStandardChecked;
}
