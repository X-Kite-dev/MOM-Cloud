package com.kitex.mom.common.message.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 消息推送配置表
 *
 * @author kitex
 * @date 2025-01-14 08:44:24
 */
@Data
@TenantTable
@TableName("message_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "消息推送配置表")
public class MessageConfigEntity extends BaseEntity<MessageConfigEntity> {

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
     * 类型
     */
    @Schema(description = "类型")
    private String type;

    /**
     * 应用ID
     */
    @Schema(description = "应用ID")
    private String appId;

    /**
     * 应用密钥
     */
    @Schema(description = "应用密钥")
    private String appSecret;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "messageId")
    private List<MessageConfigFieldEntity> messageConfigFieldList;
}