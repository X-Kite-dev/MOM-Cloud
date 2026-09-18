package com.kitex.mom.common.message.dto;


import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;
import com.kitex.mom.common.message.entity.MessageConfigEntity;
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
public class MessageDTO {

    /**
     * HandleCode
     */
    @Schema(description = "HandleCode")
    private String code;

    /**
     * 用户主键列表
     */
    @Schema(description = "用户主键列表")
    private List<Long> userList;

    /**
     * 用户第三方应用id列表
     */
    @Schema(description = "用户第三方应用id列表")
    private String userIds;

    /**
     * 消息类型
     */
    @Schema(description = "消息类型")
    @NotNull(message = "消息类型不能为空")
    private String messageType;

    /**
     * 消息
     */
    @Schema(description = "消息")
    @NotNull(message = "消息不能为空")
    private String message;

    /**
     * 调用方允许的投递通道。空值保持原业务配置行为；SYSTEM 表示站内信，
     * 其余值必须与业务消息当前绑定的外部通道类型一致。
     */
    @Schema(description = "本次允许投递的通道，例如SYSTEM,DINGDING,WECHAT")
    private List<String> requestedChannels;

    /**
     * 业务消息配置
     */
    @Schema(description = "业务消息配置")
    private MessageBusinessConfigEntity messageBusinessConfig;

    /**
     * 消息推送配置
     */
    @Schema(description = "消息推送配置")
    private MessageConfigEntity messageConfig;

}
