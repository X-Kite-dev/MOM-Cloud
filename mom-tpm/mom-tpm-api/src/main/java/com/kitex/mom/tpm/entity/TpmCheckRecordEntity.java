package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 点/巡检记录
 *
 * @author kitex
 * @date 2025-02-18 17:46:05
 */
@Data
@TenantTable
@TableName("tpm_check_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "点/巡检记录")
public class TpmCheckRecordEntity extends BaseEntity<TpmCheckRecordEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 任务号
     */
    @Schema(description = "任务号")
    private String taskCode;

    /**
     * 类型  spot 点检 patrol巡检
     */
    @Schema(description = "类型  spot 点检 patrol巡检")
    private String type;

    /**
     * 来源  设备开机 周期计划
     */
    @Schema(description = "来源  设备开机 周期计划")
    private String source;

    /**
     * 计划id
     */
    @Schema(description = "计划id")
    private Long planId;

    /**
     * 计划名称
     */
    @Schema(description = "计划名称")
    private String planName;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String deviceName;

    /**
     * 处理人编码
     */
    @Schema(description = "处理人编码")
    private String handlePersonCode;

    /**
     * 处理人名称
     */
    @Schema(description = "处理人名称")
    private String handlePersonName;

    /**
     * 点检时间
     */
    @Schema(description = "点检时间")
    private LocalDateTime handleTime;

    /**
     * 判断结果 0 通过  1 不通过
     */
    @Schema(description = "判断结果 0 通过  1 不通过")
    private String judgeResult;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "任务明细")
    private List<TpmCheckTaskDetailsEntity> detailsList;
}