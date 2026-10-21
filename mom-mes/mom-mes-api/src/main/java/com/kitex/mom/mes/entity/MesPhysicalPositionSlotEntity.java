package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 原完整布局逐位观察；不得将存在状态当质量状态或可消费余额。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_position_slot")
@Schema(description = "原完整布局逐位观察；不得将存在状态当质量状态或可消费余额")
public class MesPhysicalPositionSlotEntity extends BaseEntity<MesPhysicalPositionSlotEntity> {
    /**
     * 与原采集主账一致的工厂。
     */
    private String factoryCode;
    /**
     * 原位置观察主账ID。
     */
    private Long surveyId;
    /**
     * 原正式工程完整集合内唯一位置编码。
     */
    private String positionCode;
    /**
     * STOCKED/UNSTOCKED/MISSING/UNKNOWN，永久原观察。
     */
    private String presence;
    /**
     * 已明确有件为1、缺位为0、未知为null，不是合格数量。
     */
    private Long physicalCount;
}
