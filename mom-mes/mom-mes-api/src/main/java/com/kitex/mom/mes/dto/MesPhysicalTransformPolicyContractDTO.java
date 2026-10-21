package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 派工冻结的实物转换配置证据；与前端数学政策草案分离。
 * 仅证明原配置及其摘要，执行仍须核真实单位、位置、库存和完整下游资格。
 */
@Data
@Schema(description = "派工冻结实物转换配置证据")
public class MesPhysicalTransformPolicyContractDTO {

    /**
     * 冻结算法版本，不用当前模板或新算法解释旧任务。
     */
    @Schema(description = "冻结配置版本MES_PHYSICAL_FROZEN_POLICY_V1")
    private String version;

    /**
     * 规范公开配置JSON；NONE明确未启用，其他动作保留全部文本计量和位置。
     */
    @Schema(description = "不可变原转换配置JSON")
    private String configurationJson;

    /**
     * 带冻结算法域的配置SHA-256；不是转换执行令牌。
     */
    @Schema(description = "冻结配置SHA-256")
    private String configurationFingerprint;
}
