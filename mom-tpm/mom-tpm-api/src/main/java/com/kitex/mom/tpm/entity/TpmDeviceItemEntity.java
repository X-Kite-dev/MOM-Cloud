package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 设备-项目绑定表
 *
 * @author kitex
 * @date 2025-02-19 13:41:22
 */
@Data
@TableName("tpm_device_item")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备-项目绑定表")
public class TpmDeviceItemEntity extends Model<TpmDeviceItemEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 项目id
     */
    @Schema(description = "项目id")
    private Long itemId;

    /**
     * 类型 spot 点检  patrol 巡检
     */
    @Schema(description = "类型 spot 点检  patrol 巡检")
    private String type;

    /**
     * 编码
     */
    @TableField(exist = false)
    @Schema(description = "编码")
    private String code;

    /**
     * 名称
     */
    @TableField(exist = false)
    @Schema(description = "名称")
    private String name;

    /**
     * 检验方法
     */
    @TableField(exist = false)
    @Schema(description = "检验方法")
    private String checkMethod;

    /**
     * 检验工具
     */
    @TableField(exist = false)
    @Schema(description = "检验工具")
    private String checkTool;

    /**
     * 检验标准
     */
    @TableField(exist = false)
    @Schema(description = "检验标准")
    private String checkStandard;

    /**
     * 判断类型 0 直接确认 1 填写值
     */
    @TableField(exist = false)
    @Schema(description = "判断类型 0 直接确认 1 填写值")
    private String judgeType;

    /**
     * 标准上限
     */
    @TableField(exist = false)
    @Schema(description = "标准上限")
    private BigDecimal standardMax;

    /**
     * 标准下限
     */
    @TableField(exist = false)
    @Schema(description = "标准下限")
    private BigDecimal standardMin;

    @TableField(exist = false)
    @Schema(description = "不合格处置：WARN、BLOCK、REPAIR")
    private String failureAction;

    @TableField(exist = false)
    @Schema(description = "默认预计恢复分钟数")
    private Integer defaultRecoveryMinutes;

    @TableField(exist = false)
    @Schema(description = "不合格处置指引")
    private String failureGuidance;


    @TableField(exist = false)
    @Schema(description = "新增项目id集合")
    private List<Long> itemIdList;


}
