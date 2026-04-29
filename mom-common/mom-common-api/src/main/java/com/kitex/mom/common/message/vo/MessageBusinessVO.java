package com.kitex.mom.common.message.vo;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 业务消息VO
 *
 * @author kitex
 * @date 2025-01-15 11:27:12
 */
@Data
@Schema(description = "业务消息VO")
public class MessageBusinessVO {

    /**
     * 推送第三方类型
     */
    @Schema(description = "推送第三方类型")
    private String type;

    /**
     * 推送状态
     */
    @Schema(description = "推送状态")
    private Boolean status = Boolean.FALSE;

}