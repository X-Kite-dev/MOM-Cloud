package com.kitex.mom.common.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务模块配置表
 *
 * @author kitex
 * @date 2025-01-15 09:17:26
 */
@Data
@TenantTable
@TableName("message_business_config")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "业务模块配置表")
public class MessageBusinessConfigEntity extends BaseEntity<MessageBusinessConfigEntity> {

    /**
     * 消息推送配置表id
     */
    @Schema(description = "消息推送配置表id")
    private Long messageConfigId;

    /**
     * 所属模块
     */
    @Schema(description = "所属模块")
    private String moduleCode;

    /**
     * 业务模块名称
     */
    @Schema(description = "业务模块名称")
    private String name;

    /**
     * 业务模块编码
     */
    @Schema(description = "业务模块编码")
    private String code;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 是否推送个人消息
     */
    @Schema(description = "是否推送个人消息")
    private Boolean personalStatus = Boolean.FALSE;

    /**
     * 用户第三方列表
     */
    @Schema(description = "用户第三方列表")
    private String[] personalMessage;

    /**
     * 用户主键列表
     */
    @Schema(description = "用户主键列表")
    private String[] userIds;

    /**
     * 是否推送群消息
     */
    @Schema(description = "是否推送群消息")
    private Boolean groupStatus = Boolean.FALSE;

    /**
     * 企业hook列表
     */
    @Schema(description = "企业hook列表")
    private String groupMessage;

    /**
     * 推送类型
     */
    @Schema(description = "推送类型")
    private String messageType;
}