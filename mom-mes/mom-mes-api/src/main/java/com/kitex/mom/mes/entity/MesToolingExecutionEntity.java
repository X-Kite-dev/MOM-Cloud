package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * APS锁定工装在实际派工中的领用、使用及寿命结算事实。
 */
@Data
@TenantTable
@TableName("mes_tooling_execution")
@EqualsAndHashCode(callSuper = true)
public class MesToolingExecutionEntity extends BaseEntity<MesToolingExecutionEntity> {

    /**
     * 关联派工编码。
     */
    private String arrangeCode;

    /**
     * 关联工序任务编码。
     */
    private String produceTaskCode;

    /**
     * 关联生产工单编码。
     */
    private String taskCode;

    /**
     * 本次实际领用工装编码。
     */
    private String toolingCode;

    /**
     * 领用时的工装名称。
     */
    private String toolingName;

    /**
     * 工装分类。
     */
    private String toolingType;

    /**
     * 执行状态：CHECKED_IN、IN_USE、RELEASED。
     */
    private String status;

    /**
     * 冻结计划寿命用量；JSON文本交付，避免终端小数精度丢失。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal plannedLifeUsage;

    /**
     * 实际结算寿命用量；JSON文本交付，不用JavaScript浮点数推算。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal actualLifeUsage;

    /**
     * 实际领用人员账号，不是派工可执行人员CSV集合。
     */
    private String checkInWorkerCode;

    /**
     * 实际领用人员姓名。
     */
    private String checkInWorkerName;

    /**
     * 本次领用确认时间。
     */
    private LocalDateTime checkInTime;

    /**
     * 实际开始使用时间。
     */
    private LocalDateTime startTime;

    /**
     * 释放或结算时间。
     */
    private LocalDateTime releaseTime;

    /**
     * MDM寿命扣减原号；存在时不得按未使用工装重新领用。
     */
    private String lifeConsumptionRequestId;

    /**
     * 实际所属工厂，持久化时由服务端组织上下文限定。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
