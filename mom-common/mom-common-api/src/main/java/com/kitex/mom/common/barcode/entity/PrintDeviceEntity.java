package com.kitex.mom.common.barcode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gitee.sunchenbin.mybatis.actable.annotation.Column;
import com.gitee.sunchenbin.mybatis.actable.annotation.Table;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 打印设备
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@TableName("print_device")
@Table(comment = "打印设备")
@Schema(description = "打印设备")
public class PrintDeviceEntity extends BaseEntity<PrintDeviceEntity> {

    /**
     * 名称
     */
    @Column(comment = "名称")
    @Schema(description = "名称")
    private String name;

    /**
     * 编码
     */
    @Column(comment = "编码")
    @Schema(description = "编码")
    private String code;

    /**
     * 描述
     */
    @Column(comment = "描述")
    @Schema(description = "描述")
    private String description;

    /**
     * 标签尺寸
     */
    @Column(comment = "标签尺寸")
    @Schema(description = "标签尺寸")
    private String labelSize;

    /**
     * 地址
     */
    @Column(comment = "地址")
    @Schema(description = "地址")
    private String address;

    /**
     * ip
     */
    @Column(comment = "ip")
    @Schema(description = "ip")
    private String ip;

    /**
     * 端口
     */
    @Column(comment = "端口")
    @Schema(description = "端口")
    private String port;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
