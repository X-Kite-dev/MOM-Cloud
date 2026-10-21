package com.kitex.mom.mes.entity;

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
 * 载具状态变更和整载具报工的永久幂等回执。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_carrier_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产载具永久操作回执")
public class MesCarrierOperationEntity extends BaseEntity<MesCarrierOperationEntity> {
    private Long carrierId;
    private String carrierCode;
    private String requestId;
    private String requestFingerprint;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private Integer beforeVersion;
    private Integer afterVersion;
    private Integer itemCount;
    private String itemSnapshot;
    private String resultSnapshot;
    private Long operatorId;
    private String operatorCode;
    private String operatorName;
    private String reason;
    private LocalDateTime operatedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
