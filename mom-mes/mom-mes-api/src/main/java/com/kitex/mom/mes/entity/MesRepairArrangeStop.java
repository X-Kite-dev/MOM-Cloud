package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * TPM新报修同事务冻结的MES在制派工停工事实。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_repair_arrange_stop")
public class MesRepairArrangeStop extends BaseEntity<MesRepairArrangeStop> {
    private String factoryCode;
    private Long repairTaskId;
    private Long deviceHoldId;
    private Long arrangeId;
    private Long segmentId;
    private String deviceCode;
    private String arrangeCode;
    private LocalDateTime stopTime;
    private String segmentMode;
}
