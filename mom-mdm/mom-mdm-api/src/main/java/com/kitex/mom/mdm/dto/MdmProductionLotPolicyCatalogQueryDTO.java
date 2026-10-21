package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PLAN读取指定时点有效生产批量策略的内部查询。
 */
@Data
@Accessors(chain = true)
public class MdmProductionLotPolicyCatalogQueryDTO {
    @Schema(description = "查询工厂，须与当前调用上下文factory-code一致")
    private String factoryCode;

    @Schema(description = "请求产品编码范围，最多500项，不允许通过空范围查询全部产品")
    private List<String> productCodes;

    @Schema(description = "查询业务时刻，未提供时使用服务端当前时间；有效期左闭右开")
    private LocalDateTime effectiveTime;
}
