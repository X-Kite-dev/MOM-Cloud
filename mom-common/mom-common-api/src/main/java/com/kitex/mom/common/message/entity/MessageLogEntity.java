package com.kitex.mom.common.message.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 消息推送记录
 *
 * @author kitex
 * @date 2025-01-13 16:29:19
 */
@Data
@TenantTable
@TableName("message_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "消息推送记录")
public class MessageLogEntity extends BaseEntity<MessageLogEntity> {

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
     * 第三方账号被推送人
     */
    @Schema(description = "第三方账号被推送人")
    private String pushUser;

    /**
     * 用户主键列表
     */
    @Schema(description = "用户主键列表")
    private String[] userIds;

    /**
     * 推送时间
     */
    @Schema(description = "推送时间")
    private LocalDateTime time;

    /**
     * 推送标题
     */
    @Schema(description = "推送标题")
    private String title;

    /**
     * 推送内容
     */
    @Schema(description = "推送内容")
    private String content;

    /**
     * 结果
     */
    @Schema(description = "结果")
    private Boolean result;
}