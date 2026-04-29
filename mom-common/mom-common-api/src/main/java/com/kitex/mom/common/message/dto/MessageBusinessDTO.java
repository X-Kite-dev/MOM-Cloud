package com.kitex.mom.common.message.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 业务消息发送DTO
 *
 * @author kitex
 * @date 2025-01-15 10:54:32
 */
@Data
@Schema(description = "业务消息发送DTO")
public class MessageBusinessDTO {

    /**
     * 业务消息编码
     */
    @Schema(description = "业务消息编码")
    @NotNull(message = "业务消息编码不能为空")
    private String code;

    /**
     * 消息
     */
    @Schema(description = "消息")
    @NotNull(message = "消息不能为空")
    private String message;

}