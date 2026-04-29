package com.kitex.mom.common.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息推送配置表
 *
 * @author kitex
 * @date 2025-01-14 08:44:24
 */
@Data
@TenantTable
@TableName("message_config_field")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "message_config_field")
public class MessageConfigFieldEntity extends BaseEntity<MessageConfigFieldEntity> {
    /**
     * 消息推送配置表id
     */
    @Schema(description = "消息推送配置表id")
    private Long messageId;
    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;
    /**
     * 编码
     */
    @Schema(description = "编码")
    private String code;
    /**
     * 参数
     */
    @Schema(description = "参数")
    private String param;
}