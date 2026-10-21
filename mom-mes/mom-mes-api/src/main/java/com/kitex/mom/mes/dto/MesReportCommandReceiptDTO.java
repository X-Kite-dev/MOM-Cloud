package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 原号查询只读回执；成功事实与当前有效状态分开，避免把已冲销报工误认为仍有效。
 */
@Data
@Accessors(chain = true)
@Schema(description = "报工原号查询回执")
public class MesReportCommandReceiptDTO {

    /**
     * 原业务请求号。
     */
    @Schema(description = "请求号")
    private String requestId;

    /**
     * 十进制文本，避免前端Long精度丢失。
     */
    @Schema(description = "原报工ID（文本）")
    private String reportId;

    /**
     * 永久账记录的首次受理状态。
     */
    @Schema(description = "首次受理状态")
    private String acceptedStatus;

    /**
     * 原报工当前状态，如EFFECTIVE或REVERSED；不会覆盖原回执。
     */
    @Schema(description = "报工当前有效状态")
    private String currentReportStatus;

    /**
     * 首次成功冻结的结果JSON。
     */
    @Schema(description = "原始成功结果JSON")
    private String resultSnapshot;

    /**
     * 原命令算法及完整原正文，只返回当前实名本人的输入以核对终端持久记录。
     */
    @Schema(description = "原命令算法版本")
    private String commandVersion;

    /**
     * 服务端原始JSON，包含真实身份、入口及全部公开输入，不包含令牌。
     */
    @Schema(description = "原完整命令JSON")
    private String commandSnapshot;

    /**
     * 原始正文指纹，不用当前外码关系计算。
     */
    @Schema(description = "原命令SHA-256")
    private String requestFingerprint;

    /**
     * CLOSED_UNACCEPTED只表示旧号已封存未执行，不是生产成功。
     */
    @Schema(description = "未受理原号封存原因")
    private String closeReason;
}
