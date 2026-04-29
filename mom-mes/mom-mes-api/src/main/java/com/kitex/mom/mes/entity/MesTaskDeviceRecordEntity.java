package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 生产报工-设备开关机记录 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-04-21
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "生产报工-设备开关机记录")
@TableName("mes_task_device_record")
public class MesTaskDeviceRecordEntity extends BaseEntity<MesTaskDeviceRecordEntity> {

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
     * 设备点检记录
     */
    @Schema(description = "设备点检记录")
    private String deviceDetails;


    /**
     * 模具编码
     */
    @Schema(description = "模具编码")
    private String moldCode;

    /**
     * 模具名称
     */
    @Schema(description = "模具名称")
    private String moldName;

    /**
     * 模具点检记录
     */
    @Schema(description = "模具点检记录")
    private String moldDetails;

    /**
     * 工作中心
     */
    @Schema(description = "工作中心")
    private String worksiteCode;

    /**
     * 是否换模
     */
    @Schema(description = "是否换模")
    private Boolean changeMold;

    /**
     * 操作明细
     */
    @Schema(description = "操作明细")
    private String content;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

