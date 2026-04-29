package com.kitex.mom.common.message.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 消息发送DTO
 *
 * @author kitex
 * @date 2025-01-13 14:49:27
 */
@Data
@Schema(description = "消息发送DTO")
public class MessageLinkDTO {

    /**
     * 用户第三方应用id列表
     */
    @Schema(description = "用户第三方应用id列表")
    @NotNull(message = "用户第三方应用id列表不能为空")
    private List<String> useridList;

    /**
     * 推送的第三方类型
     */
    @Schema(description = "推送的第三方类型")
    @NotNull(message = "推送的第三方类型不能为空")
    private String pushType;

    /**
     * 消息推送配置编码
     */
    @Schema(description = "消息推送配置编码")
    @NotNull(message = "消息推送配置编码不能为空")
    private String code;

    /**
     * 消息类型
     */
    @Schema(description = "消息类型")
    @NotNull(message = "消息类型不能为空")
    private String messageType;

    /**
     * 是否群发
     */
    @Schema(description = "是否群发")
    private Boolean group = false;

    /**
     * 消息
     */
    @Schema(description = "消息")
    @NotNull(message = "消息不能为空")
    private String message;

}