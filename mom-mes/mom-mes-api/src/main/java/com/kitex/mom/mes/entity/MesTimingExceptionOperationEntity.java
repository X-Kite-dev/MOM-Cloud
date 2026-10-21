package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 时间例外永久操作账；同号同文恢复，不修改或删除历史审批。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_timing_exception_operation")
@Schema(description = "时间例外永久操作账；同号同文恢复，不修改或删除历史审批。")
public class MesTimingExceptionOperationEntity extends BaseEntity<MesTimingExceptionOperationEntity> {
    /**
     * APPLIED实际生效，DECLINED确认本动作未生效；均是永久原号结果。
     */
    @Schema(description = "永久动作结果")
    private String outcome;

    /**
     * DECLINED的明确原因，不以没有记录推断安全重投。
     */
    @Schema(description = "未生效原因")
    private String errorMessage;

    /**
     * 实际授权工厂
     */
    @Schema(description = "实际授权工厂")
    private String factoryCode;

    /**
     * 原时间例外主账ID
     */
    @Schema(description = "原时间例外主账ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long caseId;

    /**
     * 永久动作请求号
     */
    @Schema(description = "永久动作请求号")
    private String requestId;

    /**
     * SUBMIT/WITHDRAW/APPROVE/REJECT/REVOKE
     */
    @Schema(description = "SUBMIT/WITHDRAW/APPROVE/REJECT/REVOKE")
    private String action;

    /**
     * 原六字段命令正文，未知结果只重放原文
     */
    @Schema(description = "原六字段命令正文，未知结果只重放原文")
    private String commandJson;

    /**
     * 原命令域分离SHA256
     */
    @Schema(description = "原命令域分离SHA256")
    private String fingerprint;

    /**
     * 操作前主账版本
     */
    @Schema(description = "操作前主账版本")
    private Integer oldVersion;

    /**
     * 操作后主账版本
     */
    @Schema(description = "操作后主账版本")
    private Integer newVersion;

    /**
     * 实际操作人实名ID
     */
    @Schema(description = "实际操作人实名ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    /**
     * 实际操作人账号
     */
    @Schema(description = "实际操作人账号")
    private String operatorCode;

    /**
     * 原操作原因
     */
    @Schema(description = "原操作原因")
    private String reason;

    /**
     * 本动作申请的截止，其他动作为空
     */
    @Schema(description = "本动作申请的截止，其他动作为空")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime proposedUntil;

    /**
     * 数据库操作时刻
     */
    @Schema(description = "数据库操作时刻")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    private LocalDateTime createdAt;

    /**
     * 本动作不可变结果，不用当前状态冒充历史
     */
    @Schema(description = "本动作不可变结果，不用当前状态冒充历史")
    private String resultJson;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}
