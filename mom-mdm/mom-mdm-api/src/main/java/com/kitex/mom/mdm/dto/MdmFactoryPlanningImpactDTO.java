package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 工厂计划权威切换前的跨模块影响证据。 */
@Data
@Accessors(chain = true)
@Schema(description = "工厂计划权威发布影响检查")
public class MdmFactoryPlanningImpactDTO {
    private String draftId;
    private String factoryCode;
    private Integer draftVersion;
    private String draftFingerprint;
    private ModuleImpact plan;
    private ModuleImpact mes;
    private Boolean immediateSwitchAllowed;
    private List<String> blockers = new ArrayList<>();
    private String confirmationNo;
    private LocalDateTime inspectedAt;

    /** 单个业务模块返回的可核验计数；不可用时 available=false。 */
    @Data
    @Accessors(chain = true)
    public static class ModuleImpact {
        private String module;
        private String factoryCode;
        private Boolean available;
        private Long activeMrpCount;
        private Long lockedApsCount;
        private Long unfinishedTaskCount;
        private Long pendingErpEventCount;
        private String message;
    }
}
