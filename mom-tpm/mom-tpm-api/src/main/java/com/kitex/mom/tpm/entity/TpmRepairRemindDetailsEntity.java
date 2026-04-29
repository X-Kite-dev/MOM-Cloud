package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提醒配置详情表
 *
 * @author kitex
 * @date 2025-02-06 16:05:30
 */
@Data
@TableName("tpm_repair_remind_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "提醒配置详情表")
public class TpmRepairRemindDetailsEntity extends Model<TpmRepairRemindDetailsEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 提醒编号
     */
    @Schema(description = "提醒编号")
    private String remindCode;

    /**
     * 人员类型 0 处理人 1通知人
     */
    @Schema(description = "人员类型 0 处理人 1通知人")
    private String personType;

    /**
     * 角色id
     */
    @Schema(description = "角色id")
    private Long roleId;

    /**
     * 角色名称
     */
    @Schema(description = "角色名称")
    private String roleName;

    /**
     * 处理/通知人编码
     */
    @Schema(description = "处理/通知人编码")
    private String userIds;

    /**
     * 处理/通知人名称
     */
    @Schema(description = "处理/通知人名称")
    private String names;

    /**
     * 处理时限（分钟）
     */
    @Schema(description = "处理时限（分钟）")
    private Integer handleLimit;


    /**
     * 时长单位
     */
    @Schema(description = "时长单位")
    private String handleUnit;

    /**
     * 通知人级别
     */
    @Schema(description = "通知人级别")
    private Integer userLevel;
}