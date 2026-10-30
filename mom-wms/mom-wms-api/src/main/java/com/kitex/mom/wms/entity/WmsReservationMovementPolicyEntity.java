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

/**
 * 已实现预留闭环的作业类型策略；库存下限不属于此配置。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_reservation_movement_policy")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "WMS作业类型预留策略")
public class WmsReservationMovementPolicyEntity extends BaseEntity<WmsReservationMovementPolicyEntity> {

    @Schema(description = "作业类型：produce生产领料、sales销售出库")
    private String movementType;

    @Schema(description = "是否允许新建正式库存预留")
    private Boolean reservationAllowed;

    @Schema(description = "是否启用该策略；停用时沿用原有行为")
    private Boolean status;

    @Schema(description = "说明")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
