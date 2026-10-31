package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** Immutable original command; task callback fields remain the delivery progress authority. */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "mes_rework_completion_outbox", schema = "mom_mes")
public class MesReworkCompletionOutboxEntity {
    @TableId private Long id;
    private Long tenantId;
    private String factoryCode;
    private Long taskId;
    private String requestId;
    private String payload;
    private String payloadFingerprint;
    private Long createdBy;
    private Date createdAt;
}
