package com.kitex.mom.plan.entity;

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
 * 定时或人工效果回评产生的待确认偏差行动；不是已批准的重排案件。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_scheduling_effect_action")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "APS执行效果超阈值待确认行动")
public class PlnSchedulingEffectActionEntity extends BaseEntity<PlnSchedulingEffectActionEntity> {
    /**
     * 来源效果回评主账。
     */
    private Long reviewId;
    /**
     * 首次触发的不可变观察记录。
     */
    private Long observationId;
    /**
     * MES执行事实行动指纹，排除采集时间及独立分钟证据波动；同一事实不产生重复行动。
     */
    private String effectFingerprint;
    /**
     * 触发观察时采用的允许偏差分钟数。
     */
    private Integer toleranceMinutes;
    /**
     * PENDING、CONFIRMED、RESOLVED或SUPERSEDED。
     */
    private String actionStatus;
    /**
     * 可供计划员核对的阈值和偏差事实，不是模型结论。
     */
    private String triggerReason;
    /**
     * 人工确认后建立或复用的统一APS案件。
     */
    private Long confirmedCaseId;
    /**
     * 完成人工确认的真实登录账号。
     */
    private String confirmedBy;
    /**
     * 完成人工确认的时间。
     */
    private LocalDateTime confirmedTime;
    /**
     * 与效果回评和观察一致的工厂编码。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
