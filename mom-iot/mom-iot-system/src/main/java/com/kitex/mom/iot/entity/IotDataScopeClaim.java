package com.kitex.mom.iot.entity;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
@Data @Accessors(chain=true) @EqualsAndHashCode(callSuper=true)
@TenantTable @TableName("iot_data_scope_claim")
public class IotDataScopeClaim extends BaseEntity<IotDataScopeClaim> {
    private Long deviceId;
    private String deviceCode;
    private String sourceKind;
    private String status;
    private String reason;
    private String evidenceRefs;
    private String fingerprint;
    private String deviceSnapshot;
    private Long applicantId;
    private Boolean applicantAttested;
    private Boolean reviewerAttested;
    private Long reviewerId;
    private java.time.LocalDateTime submittedTime;
    private java.time.LocalDateTime reviewedTime;
    private String reviewReason;
    private Integer sourceCount;
    private String factoryCode;
}
