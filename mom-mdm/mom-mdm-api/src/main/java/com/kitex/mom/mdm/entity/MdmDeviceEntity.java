package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 设备实体类 - 用于管理生产设备的基础信息
 * 包含设备的基本属性、技术参数、状态信息等
 *
 * @author kitex
 * @date 2024-10-28 17:29:52
 */
@Data
@TenantTable
@TableName("mdm_device")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备")
public class MdmDeviceEntity extends BaseEntity<MdmDeviceEntity> {

    /**
     * 设备编码 - 设备的唯一标识编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 设备名称 - 设备的中文名称，用于显示和识别
     */
    @Schema(description = "设备名称")
    private String deviceName;

    /**
     * 设备规格 - 设备的技术规格参数
     */
    @Schema(description = "设备规格")
    private String spec;

    /**
     * 设备型号 - 设备的具体型号标识
     */
    @Schema(description = "设备型号")
    private String model;

    /**
     * 设备制造商 - 设备的生产厂家名称
     */
    @Schema(description = "设备制造商")
    private String manufacturer;

    /**
     * 生产日期 - 设备的生产制造日期
     */
    @Schema(description = "生产日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate productionDate;

    /**
     * 采购价格 - 设备的采购价格，单位：元
     */
    @Schema(description = "采购价格（单位：元）")
    private BigDecimal purchasePrice;

    /**
     * 保修截止日期 - 设备保修服务的截止日期
     */
    @Schema(description = "保修截止日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate repairDate;

    /**
     * 附件URL集合 - 设备相关附件的URL地址集合，用逗号分隔
     */
    @Schema(description = "附件URL集合")
    private String attachmentUrls;

    /**
     * 图片URL集合 - 设备相关图片的URL地址集合，用逗号分隔
     */
    @Schema(description = "图片URL集合")
    private String imageUrls;

    /**
     * 设备分类编码 - 设备所属分类的编码
     */
    @Schema(description = "设备分类编码")
    private String classCode;

    /**
     * 设备分类名称 - 设备所属分类的名称
     */
    @Schema(description = "设备分类名称")
    private String className;

    /**
     * 设备位置 - 设备安装或存放的具体位置描述
     */
    @Schema(description = "设备位置")
    private String location;

    /**
     * 设备可用状态 - 设备是否可用进行生产（0-不可用，1-可用）
     */
    @Schema(description = "设备可用状态")
    private String deviceUsable;

    /**
     * 设备状态 - 设备当前的运行状态
     * 0-空闲，1-保养中，2-维修中，3-已报废，4-进行中，5-暂停中
     */
    @Schema(description = "设备状态")
    private String deviceState;

    /**
     * 状态 - 数据的启用状态（true-启用，false-禁用）
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    private List<MdmDeviceEntity> deviceList;


    @TableField(exist = false)
    private List<String> neCodeList;
}
