package com.kitex.mom.iot.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * IoT设备分组
 *
 * @author system
 * @date 2025-03-12
 */
@Data
@TenantTable
@TableName("iot_device_group")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "IoT设备分组")
public class IotDeviceGroupEntity extends BaseEntity<IotDeviceGroupEntity> {

    /**
     * 分组名称
     */
    @Schema(description = "分组名称")
    private String groupName;

    /**
     * 分组编码
     */
    @Schema(description = "分组编码")
    private String groupCode;

    /**
     * 父分组ID，0表示顶级分组
     */
    @Schema(description = "父分组ID")
    private Long parentId;

    /**
     * 分组描述
     */
    @Schema(description = "分组描述")
    private String description;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 子分组列表(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "子分组列表")
    private List<IotDeviceGroupEntity> children;

    /**
     * 设备数量(非数据库字段)
     */
    @TableField(exist = false)
    @Schema(description = "设备数量")
    private Integer deviceCount;
} 