package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 生产批量策略启用、暂停或退役命令。
 */
@Data
public class MdmProductionLotPolicyActionDTO {
    @Schema(description = "策略版本主键")
    private Long id;

    @Schema(description = "ACTIVATE启用、SUSPEND暂停、RETIRE退役")
    private String action;

    @Schema(description = "暂停或退役原因，最多500字")
    private String reason;

    @Schema(description = "页面复核时看到的状态，须与锁内当前记录一致")
    private String expectedStatus;

    @Schema(description = "必须回传复核详情的版本令牌，防止同秒修改或时间格式精度损失")
    private String expectedRevisionToken;
}
