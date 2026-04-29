package com.kitex.mom.common.message.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 业务模块配置新增DTO
 *
 * @author kitex
 * @date 2025-01-21 08:35:32
 */
@Data
@Schema(description = "业务模块配置新增DTO")
public class MessageBusinessConfigAddDTO {

    /**
     * 业务模块表主键id
     */
    @Schema(description = "业务模块表主键id")
    private Long id;

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
     * 用户列表
     */
    @Schema(description = "用户列表")
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
    private List<MessageBusinessConfigGroupAddDTO> groupList;

    /**
     * 推送类型
     */
    @Schema(description = "推送类型")
    private String messageType;
}