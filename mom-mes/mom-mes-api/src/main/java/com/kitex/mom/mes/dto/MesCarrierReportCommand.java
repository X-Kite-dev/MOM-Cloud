package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 载具逐件展开报工命令。
 */
@Data
@Schema(description = "载具逐件展开报工命令")
public class MesCarrierReportCommand {

    @Schema(description = "8至64位永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @Schema(description = "载具编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String carrierCode;

    @Schema(description = "服务端预览返回的唯一可执行派工单", requiredMode = Schema.RequiredMode.REQUIRED)
    private String arrangeCode;

    @Schema(description = "实际工位", requiredMode = Schema.RequiredMode.REQUIRED)
    private String worksiteCode;

    @Schema(description = "实际设备编码")
    private String deviceCode;

    @Schema(description = "自动工位人工接管原因")
    private String manualOverrideReason;

    @Schema(description = "客户端读取的载具状态版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer expectedVersion;

    @Schema(description = "现场操作说明", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
