package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_counter_baseline_case")
public class MesCounterBaselineCase extends BaseEntity<MesCounterBaselineCase> {
    private String factoryCode;
    private Long ruleId;
    private Long eventId;
    private String arrangeCode;
    private BigDecimal counterValue;
    private LocalDateTime eventTime;
    private String sourceEventId;
    private String evidenceJson;
    private String evidenceFingerprint;
    private String physicalEvidence;
    private String reason;
    private String status;
    private Long applicantId;
    private Long reviewerId;
    private String reviewReason;
    private LocalDateTime reviewTime;
}
