package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能 APS 资源取证、快照和数据预检接口模型。
 *
 * <p>所有主键均按字符串输出，避免浏览器丢失雪花主键精度。业务问题只引用原模块处理入口，
 * 不在 APS 中复制维护主数据、库存、设备、质量或采购事实。</p>
 */
public final class PlnApsResourceDTO {

    private PlnApsResourceDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "资源快照采集命令")
    public static class CaptureCommand {
        private String requestId;
        private String expectedCaseFingerprint;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "资源快照与预检结论")
    public static class Snapshot {
        private String snapshotId;
        private String snapshotNo;
        private Integer versionNo;
        private String caseId;
        private String caseFingerprint;
        private String resourceFingerprint;
        private String readinessStatus;
        private Boolean complete;
        private Boolean stale;
        private String staleReason;
        private LocalDateTime capturedTime;
        private Map<String, ModuleStatus> modules;
        private Counts counts;
        private List<Issue> issues = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class ModuleStatus {
        private String module;
        private String status;
        private Boolean critical;
        private String sourceVersion;
        private String sourceFingerprint;
        private LocalDateTime observedAt;
        private Integer evidenceCount;
        private Integer gapCount;
        private String message;
    }

    @Data
    @Accessors(chain = true)
    public static class Counts {
        private Integer info;
        private Integer warning;
        private Integer blocker;
    }

    @Data
    @Accessors(chain = true)
    public static class Issue {
        private String id;
        private String issueCode;
        private String category;
        private String severity;
        private String sourceModule;
        private String sourceType;
        private String sourceCode;
        private String sourceVersion;
        private String impactedObjectType;
        private String impactedObjectId;
        private String impactedObjectCode;
        private String title;
        private String description;
        private String resolutionRoute;
        private String issueStatus;
    }
}
