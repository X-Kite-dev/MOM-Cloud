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
 * 修订执行前置的永久原件；不继承可更新/删除业务基类。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "mes_inspection_revision_cancel", schema = "mom_mes")
public class MesInspectionRevisionCancelEntity {
    @TableId
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    private String factoryCode;
    private String taskCode;
    private String produceTaskCode;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long inspectionId;

    private String intent;
    private String intentFingerprint;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long canceledBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    private Date canceledAt;

    private String reason;
}
