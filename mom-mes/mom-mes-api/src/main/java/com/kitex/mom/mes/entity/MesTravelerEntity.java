package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 多流转卡/批次载具主档；同一生产工单允许绑定多张卡。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_traveler")
@EqualsAndHashCode(callSuper = true)
public class MesTravelerEntity extends BaseEntity<MesTravelerEntity> {
    private String travelerCode;
    private String taskCode;
    private String matCode;
    private String matName;
    private BigDecimal initialQuantity;
    private String status;
    private String firstRouteCode;
    private String latestRouteCode;
    private Integer latestRouteOrder;
    private LocalDateTime bindTime;
    private LocalDateTime completeTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
