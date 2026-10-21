package com.kitex.mom.iot.entity;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
@Data @Accessors(chain=true) @EqualsAndHashCode(callSuper=true)
@TenantTable @TableName("iot_data_scope_operation")
public class IotDataScopeOperation extends BaseEntity<IotDataScopeOperation> {
    private Long claimId;
    private String requestId;
    private String fingerprint;
    private String action;
    private String reason;
    private Long operatorId;
    private java.time.LocalDateTime operationTime;
    private String resultStatus;
    private String factoryCode;
}

