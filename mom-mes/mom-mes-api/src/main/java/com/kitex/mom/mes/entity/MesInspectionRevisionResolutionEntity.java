package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

@Data
@Accessors(chain = true)
@TableName(value = "mom_mes.mes_inspection_revision_resolution", autoResultMap = true)
public class MesInspectionRevisionResolutionEntity {
    @TableId
    private Long id;
    private Long tenantId;
    private String factoryCode;
    private String taskCode;
    private String produceTaskCode;
    private Long inspectionId;
    private Long genRecordId;
    private String operation;
    private String intentFingerprint;
    private String sealFingerprint;
    private Long eventId;
    private String eventFingerprint;
    private String payload;
    private String fingerprint;
    private String receipt;
    private String receiptFingerprint;
    private Date resolvedAt;
}
