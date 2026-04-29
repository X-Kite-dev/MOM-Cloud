package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 维修任务详情表
 *
 * @author kitex
 * @date 2025-02-06 16:10:11
 */
@Data
@TableName("tpm_repair_task_details")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "维修任务详情表")
public class TpmRepairTaskDetailsEntity extends Model<TpmRepairTaskDetailsEntity> {


    /**
     * ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "ID")
    private Long id;

    /**
     * 维修任务编码
     */
    @Schema(description = "维修任务编码")
    private String taskCode;

    /**
     * 备件编码
     */
    @Schema(description = "备件编码")
    private String sparePartsCode;

    /**
     * 备件名称
     */
    @Schema(description = "备件名称")
    private String sparePartsName;

    /**
     * 库房编码
     */
    @Schema(description = "库房编码")
    private String storeroomCode;

    /**
     * 库房名称
     */
    @Schema(description = "库房名称")
    private String storeroomName;


    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String locationCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String locationName;

    /**
     * 规格
     */
    @Schema(description = "规格")
    private String specification;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;

    /**
     * 使用数量
     */
    @Schema(description = "使用数量")
    private BigDecimal useNum;

    /**
     * 使用时间
     */
    @Schema(description = "使用时间")
    private LocalDateTime useTime;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}