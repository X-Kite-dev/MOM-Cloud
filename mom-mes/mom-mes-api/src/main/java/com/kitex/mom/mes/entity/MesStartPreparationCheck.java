package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 不可变开工准备观察快照。仅记录事实，不预留物料、不签收SOP、不放行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_start_preparation_check")
public class MesStartPreparationCheck extends BaseEntity<MesStartPreparationCheck> {
    /**
     * 工厂隔离域。
     */
    private String factoryCode;
    /**
     * 永久请求及规范内容摘要。
     */
    private String requestId;
    private String requestHash;
    /**
     * 原派工和工单身份，不根据名称匹配。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;
    private String arrangeCode;
    private String taskCode;
    /**
     * 此快照依据的真实操作者，签收/指定作业者检查对其有效。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private String contractVersion;
    /**
     * READY/BLOCKED/UNKNOWN，不等同开工许可。
     */
    private String status;
    /**
     * 全项来源快照和排除观察时间后的稳定事实摘要。
     */
    private String snapshotJson;
    private String evidenceFingerprint;
    private LocalDateTime observedAt;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}
