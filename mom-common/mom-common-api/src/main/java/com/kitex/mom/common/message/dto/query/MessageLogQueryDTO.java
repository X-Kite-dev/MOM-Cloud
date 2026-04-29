package com.kitex.mom.common.message.dto.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 消息推送记录查询DTO
 *
 * @author kitex
 * @date 2025-01-16 11:05:55
 */
@Data
@Schema(description = "消息推送记录查询DTO")
public class MessageLogQueryDTO {

    /**
     * 社交登录类型
     */
    @Schema(description = "社交登录类型")
    private String type;

    /**
     * 所属模块
     */
    @Schema(description = "所属模块")
    private String moduleCode;

    /**
     * 业务模块类型
     */
    @Schema(description = "业务模块类型")
    private String businessType;

    /**
     * 消息类型
     */
    @Schema(description = "消息类型")
    private String messageType;

    /**
     * 推送人
     */
    @Schema(description = "推送人")
    private String user;

    /**
     * 推送时间
     */
    @Schema(description = "推送时间")
    private LocalDate[] time;

    /**
     * 结果
     */
    @Schema(description = "结果")
    private Boolean result;
}