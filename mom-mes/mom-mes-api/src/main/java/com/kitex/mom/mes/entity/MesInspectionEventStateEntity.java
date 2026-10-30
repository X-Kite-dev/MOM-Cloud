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
 * 已接管流的本地消费游标；CONFLICT不能通过普通重试清除。id为生成记录ID。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "mes_inspection_event_state", schema = "mom_mes")
public class MesInspectionEventStateEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long inspectionId;

    private String taskCode;
    private String produceTaskCode;
    private Long lastSequence;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastEventId;

    private String lastFingerprint;
    private String status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long blockedEventId;

    private String lastError;
    private Long version;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date updatedAt;
}
