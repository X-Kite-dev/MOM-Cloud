package com.kitex.mom.mdm.entity;

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
 * 仓库
 *
 * @author kitex
 * @date 2024-10-28 13:48:36
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mdm_warehouse")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库")
public class MdmWarehouseEntity extends BaseEntity<MdmWarehouseEntity> {

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 仓库类型
     */
    @Schema(description = "仓库类型")
    private String warehouseType;

    /**
     * 是否VMI (1=是, 0=否)
     */
    @Schema(description = "是否VMI (1=是, 0=否)")
    private Integer isVmi;

    /**
     * 是否启用仓位管理 (1=是, 0=否)
     */
    @Schema(description = "是否启用仓位管理 (1=是, 0=否)")
    private Integer isLocationManagement;

    /**
     * 是否手机端 (1=是, 0=否)
     */
    @Schema(description = "是否手机端 (1=是, 0=否)")
    private Integer isMobile;

    /**
     * 是否自动生成批次 (1=是, 0=否)
     */
    @Schema(description = "是否自动生成批次 (1=是, 0=否)")
    private Integer isBatch;

    /**
     * 是否强制使用容器 (1=是, 0=否)
     */
    @Schema(description = "是否强制使用容器 (1=是, 0=否)")
    private Integer isContainer;

    /**
     * 说明
     */
    @Schema(description = "说明")
    private String description;

    /**
     * 仓库负责人
     */
    @Schema(description = "仓库负责人")
    private String warehouseManager;

    /**
     * 仓库地址
     */
    @Schema(description = "仓库地址")
    private String warehouseAddress;

    /**
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 工厂名称
     */
    @Schema(description = "工厂名称")
    private String factoryName;

    /**
     * 是否跳过工厂过滤
     */
    @TableField(exist = false)
    @Schema(description = "是否跳过工厂过滤")
    private Boolean skipFactory;

}