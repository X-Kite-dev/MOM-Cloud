package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "开始换型")
public class MesChangeoverStartDTO {
    /**
     * 本次开始计时的原号，重提不得另造号码。
     */
    @Schema(description = "开始原号，1至100字")
    private String requestId;
    /**
     * 已明确选择的实际工位，不在提交时重新读取另一工位。
     */
    @Schema(description = "实际工位编码")
    private String worksiteCode;
    /**
     * 工位上的实际设备。
     */
    @Schema(description = "实际设备编码")
    private String deviceCode;
    /**
     * 服务端只读观察返回的实际组织/实名，防止排队请求换头后落到另一工厂。
     */
    @Schema(description = "观察时租户ID文本")
    private String expectedTenantId;
    /**
     * 观察时的工厂，不用于设置或切换工厂上下文。
     */
    @Schema(description = "观察时工厂编码")
    private String expectedFactoryCode;
    /**
     * 观察时的实际用户ID。
     */
    @Schema(description = "观察时实名用户ID文本")
    private String expectedActorUserId;
}
