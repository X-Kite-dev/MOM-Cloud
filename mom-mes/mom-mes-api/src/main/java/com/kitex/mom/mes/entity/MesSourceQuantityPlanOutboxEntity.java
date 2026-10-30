package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 来源数量的PLAN通知原文及独立投递状态；事实与待发同事务保存。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "mes_source_quantity_plan_outbox", schema = "mom_mes")
public class MesSourceQuantityPlanOutboxEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;
    private String requestId;
    private String requestFingerprint;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    private String taskCode;
    private String payload;
    private String payloadFingerprint;
    private String status;
    private Long attemptCount;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Date nextAttemptAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Date createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    private Date receivedAt;

    private String lastError;
}
