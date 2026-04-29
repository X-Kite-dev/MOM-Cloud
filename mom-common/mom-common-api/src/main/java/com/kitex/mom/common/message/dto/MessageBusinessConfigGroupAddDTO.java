package com.kitex.mom.common.message.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 业务模块配置企业hook新增DTO
 *
 * @author kitex
 * @date 2025-01-21 08:40:11
 */
@Data
@Schema(description = "业务模块配置企业hook新增DTO")
public class MessageBusinessConfigGroupAddDTO {

    /**
     * 企业hook
     */
    @Schema(description = "企业hook")
    private String groupHook;

    /**
     * 密钥密钥
     */
    @Schema(description = "密钥")
    private String groupSecret;
}