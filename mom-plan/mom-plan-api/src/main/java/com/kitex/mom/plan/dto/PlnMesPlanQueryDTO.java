package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 月度主生产计划只读筛选，日期由入口统一解析，避免与持久化实体同名字段重复绑定。
 */
@Data
@Schema(description = "主生产计划月度查询")
public class PlnMesPlanQueryDTO {
    @Schema(description = "查询月份内日期，支持yyyy-MM-dd或本地日期时间")
    private String issueTime;
    private String productCode;
    private String productName;
    private String specificationModel;
}
