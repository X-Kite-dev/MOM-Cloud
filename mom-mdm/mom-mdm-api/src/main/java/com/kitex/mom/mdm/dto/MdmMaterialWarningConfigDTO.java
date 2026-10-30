package com.kitex.mom.mdm.dto;

import lombok.Data;

/**
 * 库存预警任务的只读参数；每次扫描重新观察，不作为可修改或发布的行业模板。
 */
@Data
public class MdmMaterialWarningConfigDTO {
    private Long tenantId;
    private String factoryCode;
    private String materialCode;
    private String materialName;
    private Boolean expiration;
    private Integer expirationDay;
    private Boolean slowMaterial;
    private Integer slowMaterialDay;
}
