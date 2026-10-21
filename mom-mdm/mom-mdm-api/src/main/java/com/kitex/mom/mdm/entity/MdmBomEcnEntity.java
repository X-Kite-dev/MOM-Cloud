package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mdm_bom_ecn")
public class MdmBomEcnEntity extends BaseEntity<MdmBomEcnEntity> {
    private String ecnNo;
    private String productCode;
    private Long sourceBomId;
    private Long targetBomId;
    private String status;
    private String reason;
    private String disposition;
    private String snapshotJson;
    private String fingerprint;
    private Long applicantId;
    private Long reviewerId;
    private LocalDateTime submittedTime;
    private LocalDateTime reviewedTime;
    private String reviewReason;
    private String factoryCode;
    private String reviewPolicy;
    private LocalDateTime expiresTime;
}
