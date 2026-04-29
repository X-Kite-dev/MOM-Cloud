package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * TPM设备事件记录表
 *
 * @author kitex
 * @date 2025-03-18 10:07:01
 */
@Data
@TableName("tpm_event_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "TPM设备事件记录表")
public class TpmEventRecordEntity extends Model<TpmEventRecordEntity> {


    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 事件来源编码
     */
    @Schema(description = "事件来源编码")
    private String eventCode;

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
     * 事件来源
     */
    @Schema(description = "事件来源")
    private String eventSource;

    /**
     * 操作人
     */
    @Schema(description = "操作人")
    private String operator;

    /**
     * 精确到秒的事件发生时间
     */
    @Schema(description = "精确到秒的事件发生时间")
    private LocalDateTime occurredAt;

    /**
     * 事件详细描述（包含故障现象、处理过程、更换备件等）
     */
    @Schema(description = "事件详细描述（包含故障现象、处理过程、更换备件等）")
    private String description;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}