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
 * 工位过站判定账。成功记录与报工同事务提交，拒绝记录由独立事务保存。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_station_pass_record")
@EqualsAndHashCode(callSuper = true)
public class MesStationPassRecordEntity extends BaseEntity<MesStationPassRecordEntity> {
    private String requestId;
    private String requestFingerprint;
    private Long reportId;
    private String result;
    private String reasonCode;
    private String reasonMessage;
    private String reportMode;
    private String transferCode;
    private BigDecimal passQuantity;
    private String taskCode;
    private String produceTaskCode;
    private String arrangeCode;
    private String routeCode;
    private String routeName;
    private Integer routeOrder;
    private String predecessorCodes;
    private String worksiteCode;
    private String deviceCode;
    private String toolingCodes;
    private String operatorCode;
    private String operatorName;
    private String validationSnapshot;
    private String executionKind;
    private Long routeReturnId;
    private Integer identifierRouteVersion;
    private Long carrierOperationId;
    private String carrierCode;
    private LocalDateTime checkedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
