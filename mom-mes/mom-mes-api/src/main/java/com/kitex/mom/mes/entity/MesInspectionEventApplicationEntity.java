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
 * 不可变正式应用回执，与本次数量/库存变更同事务。id为原事件ID。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "mes_inspection_event_application", schema = "mom_mes")
public class MesInspectionEventApplicationEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long genRecordId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long inspectionId;

    private Long sequenceNo;
    private String eventFingerprint;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long previousEventId;

    private String previousFingerprint;
    private String decisionSnapshot;
    private String decisionFingerprint;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date appliedAt;
}
