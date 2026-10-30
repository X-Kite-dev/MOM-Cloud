package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 路线结构复制命令；双版本避免复制过期来源或覆盖已被维护的目标。
 */
@Data
@Schema(description = "仅复制路线结构及节点配置，不复制工程附件或历史")
public class MdmRouteGraphCopyDTO {

    @Schema(description = "空目标路线ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "来源路线ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sourceRouteId;

    @Schema(description = "目标原图版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long expectedGraphRevision;

    @Schema(description = "来源原图版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long expectedSourceGraphRevision;
}
