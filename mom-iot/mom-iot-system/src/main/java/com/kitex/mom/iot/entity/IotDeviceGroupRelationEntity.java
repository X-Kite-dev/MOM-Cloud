package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * IoT设备分组关联
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_device_group_relation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT设备分组关联")
public class IotDeviceGroupRelationEntity extends BaseEntity<IotDeviceGroupRelationEntity> {

    /**
     * 分组ID
     */
    @Schema(description = "分组ID")
    private Long groupId;

    /**
     * 设备ID
     */
    @Schema(description = "设备ID")
    private Long deviceId;

    /**
     * 设备信息(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "设备信息")
    private IotDeviceEntity device;

    /**
     * 分组信息(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "分组信息")
    private IotDeviceGroupEntity group;
} 