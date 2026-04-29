package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 容器占用历史
 *
 * @author kitex
 * @date 2025-01-02 15:06:10
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_container_use_history")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "容器占用历史")
public class WmsContainerUseHistoryEntity extends BaseEntity<WmsContainerUseHistoryEntity> {

    /**
     * 容器条码
     */
    @Schema(description = "容器条码")
    private String code;

    /**
     * 使用开始时间
     */
    @Schema(description = "使用开始时间")
    private LocalDateTime startTime;

    /**
     * 使用结束时间
     */
    @Schema(description = "使用结束时间")
    private LocalDateTime endTime;

    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 仓库编码
     */
    @TableField(exist = false)
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @TableField(exist = false)
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 容器类型
     */
    @TableField(exist = false)
    @Schema(description = "容器类型")
    private String typeCode;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}