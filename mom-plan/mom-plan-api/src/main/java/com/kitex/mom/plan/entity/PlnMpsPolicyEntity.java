package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工厂级MPS滚动窗口、冻结区及MRP门禁版本配置。
 */
@Data
@Accessors(chain = true)
@TenantTable
@EqualsAndHashCode(callSuper = true)
@TableName("pln_mps_policy")
public class PlnMpsPolicyEntity extends BaseEntity<PlnMpsPolicyEntity> {
    private String factoryCode;
    private Integer policyVersion;
    private Integer horizonDays;
    private Integer freezeDays;
    private Boolean enforceMps;
    /**
     * RCCP产能门禁：OFF、WARN或BLOCK。
     */
    private String rccpMode;
    /**
     * 允许承诺的最大负荷率；100表示不允许超过理论可用产能。
     */
    private BigDecimal rccpMaxLoadRate;
    private Boolean active;
    private String requestId;
    private String requestFingerprint;
    private String configuredBy;
    private LocalDateTime configuredTime;
}
