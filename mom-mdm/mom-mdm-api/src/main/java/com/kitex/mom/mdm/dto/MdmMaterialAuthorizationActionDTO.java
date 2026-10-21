package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "物料授权状态操作")
public class MdmMaterialAuthorizationActionDTO {
    private Long id;
    @Schema(description = "请求动作：ACTIVATE、SUSPEND、REVOKE")
    private String action;
    @Schema(description = "暂停或撤销原因")
    private String reason;
}
