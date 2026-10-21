package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备事件到MES报工事务的可执行映射规则。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_auto_report_rule")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备自动报工规则")
public class MesAutoReportRuleEntity extends BaseEntity<MesAutoReportRuleEntity> {
    private String ruleCode;
    private String ruleName;
    private String deviceCode;
    private String eventIdentifier;
    /**
     * DELTA / CUMULATIVE / PULSE。
     */
    private String quantityMode;
    private BigDecimal quantityMultiplier;
    private BigDecimal pulseQuantity;
    private BigDecimal maxSingleQuantity;
    private Boolean counterResetAllowed;
    private Integer maxEventDelayMinutes;
    private String routeCode;
    private String worksiteCode;
    private Boolean enabled;
    /**
     * 累计计数模式的最后一次成功状态。
     */
    private BigDecimal lastCounterValue;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastEventTime;
    private String lastSourceEventId;
    /**
     * 累计计数仅归属于已批准的派工分段，不跨派工沿用旧值。
     */
    private String counterArrangeCode;
    private Long counterBaselineCaseId;
    private Long lastCounterEventId;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
