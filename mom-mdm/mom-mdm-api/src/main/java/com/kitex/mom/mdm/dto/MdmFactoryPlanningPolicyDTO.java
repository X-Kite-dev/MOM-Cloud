package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 对外返回的工厂计划协同策略；雪花主键使用字符串避免浏览器精度损失。 */
@Data
@Schema(description = "工厂计划协同策略版本")
public class MdmFactoryPlanningPolicyDTO {
    private String id;
    private String factoryModelId;
    private String factoryCode;
    private Integer versionNo;
    private String mrpAuthority;
    private String erpSystemCode;
    private String externalFactoryCode;
    private String productionOrderSyncMode;
    private String substituteGovernanceMode;
    private LocalDateTime effectiveFrom;
    private String status;
    private String contentFingerprint;
    private String changeReason;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String submittedBy;
    private LocalDateTime submittedTime;
    private String publishedBy;
    private LocalDateTime publishedTime;
    private String activationFailureReason;
}
