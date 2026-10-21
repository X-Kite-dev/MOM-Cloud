package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("mdm_tooling_device_compatibility")
@EqualsAndHashCode(callSuper = true)
public class MdmToolingDeviceCompatibilityEntity extends BaseEntity<MdmToolingDeviceCompatibilityEntity> {
    private String toolingCode;
    private String toolingName;
    private String deviceCode;
    private String deviceName;
    private Boolean status;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
