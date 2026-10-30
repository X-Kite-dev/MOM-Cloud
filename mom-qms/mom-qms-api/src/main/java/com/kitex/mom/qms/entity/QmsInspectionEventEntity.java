package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.util.TenantTable;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** 检验动作的不可变原件；不继承可更新/逻辑删除的业务基类。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "qms_inspection_event", schema = "mom_qms")
@Schema(description = "检验动作永久原件，序号不是改判许可，不代表已向MES投递")
public class QmsInspectionEventEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;
    private String inspectionType;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long historyId;

    private String eventType;
    private Long sequenceNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long previousEventId;

    private String previousFingerprint;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date occurredAt;

    private String payload;
    private String fingerprint;
}
