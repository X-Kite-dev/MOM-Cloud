package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "取消未完成换型")
public class MesChangeoverCancelDTO {
    /**
     * 只允许取消当前组织的精确执行账。
     */
    @Schema(description = "执行账ID")
    private Long executionId;
    /**
     * 与原账同时核对，不能只拿一个ID取消另一个设备。
     */
    @Schema(description = "明确的设备编码")
    private String deviceCode;
    /**
     * 原账的实际工位。
     */
    @Schema(description = "明确的工位编码")
    private String worksiteCode;
    /**
     * 操作员提供的实际取消依据，不使用关闭页面的固定描述。
     */
    @Schema(description = "实际取消原因，1至500字")
    private String reason;
    /**
     * 仅用于与认证组织核对，不设置租户。
     */
    @Schema(description = "观察时租户ID文本")
    private String expectedTenantId;
    /**
     * 原观察的实际工厂。
     */
    @Schema(description = "观察时工厂编码")
    private String expectedFactoryCode;
    /**
     * 原观察的实际用户ID。
     */
    @Schema(description = "观察时实名用户ID文本")
    private String expectedActorUserId;
}
