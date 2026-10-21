package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "物料授权有效期预警汇总")
public class MdmMaterialAuthorizationSummaryDTO {
    private long draftCount;
    private long activeCount;
    private long expiringCount;
    private long expiredCount;
    private long suspendedCount;
    private long revokedCount;
}
