package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mdm_device_pause_receipt")
public class MdmDevicePauseReceipt extends BaseEntity<MdmDevicePauseReceipt> {
    private String factoryCode;
    private String requestId;
    private String fingerprint;
    private String deviceCode;
    private String moldCode;
    private Long operatorId;
    private String operatorCode;
    private String reason;
    private String evidenceJson;
    private String action;
}
