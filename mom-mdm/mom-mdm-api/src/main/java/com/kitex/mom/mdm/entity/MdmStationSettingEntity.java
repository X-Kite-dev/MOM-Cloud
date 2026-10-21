package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 工序-工位绑定表
 *
 * @author claude
 * @date 2025-07-30
 */
@Data
@TenantTable
@TableName("mdm_station_setting")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工序-工位绑定表")
public class MdmStationSettingEntity extends BaseEntity<MdmStationSettingEntity> {

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String procedureCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String procedureName;

    /**
     * 员工编码
     */
    @Schema(description = "员工编码")
    private String[] employeeCodes;

    /**
     * 员工名称
     */
    @Schema(description = "员工名称")
    private String[] employeeNames;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String[] deviceCodes;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String[] deviceNames;

    /**
     * 工位ID（工厂建模节点ID）
     */
    @Schema(description = "工位ID（工厂建模节点ID）")
    private Long stationId;

    /**
     * 工位编码
     */
    @Schema(description = "工位编码")
    private String stationCode;

    /**
     * 工位名称
     */
    @Schema(description = "工位名称")
    private String stationName;

    /**
     * 线体ID（工厂建模节点ID）
     */
    @Schema(description = "线体ID（工厂建模节点ID）")
    private Long lineId;

    /**
     * 线体编码
     */
    @Schema(description = "线体编码")
    private String lineCode;

    /**
     * 线体名称
     */
    @Schema(description = "线体名称")
    private String lineName;

    /**
     * 车间ID（工厂建模节点ID）
     */
    @Schema(description = "车间ID（工厂建模节点ID）")
    private Long workshopId;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 工厂ID（工厂建模节点ID）
     */
    @Schema(description = "工厂ID（工厂建模节点ID）")
    private Long factoryId;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 工厂名称
     */
    @Schema(description = "工厂名称")
    private String factoryName;

    /**
     * 是否主工位（一个工序可能对应多个工位）
     */
    @Schema(description = "是否主工位")
    private Boolean isPrimary;

    /**
     * 工序在该工位的优先级
     */
    @Schema(description = "工序在该工位的优先级")
    private Integer priority;

    /**
     * 该工位执行工序的标准工时（分钟）
     */
    @Schema(description = "该工位执行工序的标准工时（分钟）")
    private BigDecimal standardWorkingTime;

    /**
     * 工位产能（件/小时）
     */
    @Schema(description = "工位产能（件/小时）")
    private BigDecimal stationCapacity;

    /**
     * 同一工位可并发执行的独立资源槽位数，不替代设备、人员和工装各自的占用约束。
     */
    @Schema(description = "并行资源槽位数")
    private Integer parallelCapacity;

    @Schema(description = "跨工位共享资源组编码")
    private String resourceGroupCode;

    @Schema(description = "跨工位共享资源组名称")
    private String resourceGroupName;

    @Schema(description = "资源组共享并行容量")
    private Integer resourceGroupCapacity;

    /**
     * 状态（true-启用 false-禁用）
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 绑定说明
     */
    @Schema(description = "绑定说明")
    private String description;

}
