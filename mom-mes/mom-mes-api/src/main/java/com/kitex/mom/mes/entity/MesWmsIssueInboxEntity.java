package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_wms_issue_inbox")
public class MesWmsIssueInboxEntity extends BaseEntity<MesWmsIssueInboxEntity> {
    private String eventId;
    private Long wmsBillId;
    private Long pickingBillId;
    private String fingerprint;
    private String payloadJson;
    private String receiptJson;
    private LocalDateTime acceptedTime;
    private String factoryCode;
}
