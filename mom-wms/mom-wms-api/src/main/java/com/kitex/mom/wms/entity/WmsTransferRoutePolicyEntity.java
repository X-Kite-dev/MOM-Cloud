package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 仓间调拨路线时效策略。
 * 一条策略代表指定源仓、目标仓、运输方式和紧急等级在一个有效期内的承诺时效。
 */
@Data
@TenantTable
@TableName("wms_transfer_route_policy")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓间调拨路线时效策略")
public class WmsTransferRoutePolicyEntity extends BaseEntity<WmsTransferRoutePolicyEntity> {

    @Schema(description = "策略编码")
    private String policyCode;

    @Schema(description = "策略名称")
    private String policyName;

    @Schema(description = "源仓编码")
    private String sourceWarehouseCode;

    @Schema(description = "源仓名称快照")
    private String sourceWarehouseName;

    @Schema(description = "目标仓编码")
    private String targetWarehouseCode;

    @Schema(description = "目标仓名称快照")
    private String targetWarehouseName;

    @Schema(description = "运输方式：INTERNAL_VEHICLE/AGV/THIRD_PARTY/MANUAL")
    private String transportMode;

    @Schema(description = "紧急等级：NORMAL/URGENT/EMERGENCY")
    private String urgencyLevel;

    @Schema(description = "承诺提前期，单位分钟")
    private Integer leadTimeMinutes;

    @Schema(description = "日历方式：CONTINUOUS/MDM_WORKDAY")
    private String calendarMode;

    @Schema(description = "工作日开始时间；MDM_WORKDAY必填")
    private LocalTime workdayStartTime;

    @Schema(description = "工作日结束时间；MDM_WORKDAY必填")
    private LocalTime workdayEndTime;

    @Schema(description = "模式优先级，数值越小越优先")
    private Integer routePriority;

    @Schema(description = "生效日期（含）")
    private LocalDate effectiveStartDate;

    @Schema(description = "失效日期（含）；空表示长期有效")
    private LocalDate effectiveEndDate;

    @Schema(description = "是否启用")
    private Boolean status;

    @Schema(description = "说明")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
