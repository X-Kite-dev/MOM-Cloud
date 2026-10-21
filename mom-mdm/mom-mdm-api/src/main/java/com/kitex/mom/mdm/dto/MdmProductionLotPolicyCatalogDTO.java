package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmProductionLotPolicyEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 指定工厂、时点下按产品唯一解析的正式生产批量策略目录。
 */
@Data
@Accessors(chain = true)
public class MdmProductionLotPolicyCatalogDTO {
    @Schema(description = "按产品编码唯一解析的有效正式策略，包含精确版本及数量参数")
    private Map<String, MdmProductionLotPolicyEntity> policyByProduct;

    @Schema(description = "请求范围内没有有效正式策略的产品；与策略映射构成互斥且完整的范围")
    private List<String> productsWithoutPolicy;

    @Schema(description = "请求范围、缺失集合及正式策略完整参数的SHA-256证据指纹")
    private String evidenceFingerprint;
}
