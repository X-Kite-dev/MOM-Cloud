package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调拨路线策略解析契约。
 */
public final class WmsTransferRoutePolicyDTO {
    private WmsTransferRoutePolicyDTO() {
    }

    @Data
    public static class BatchQuery {
        private String factoryCode;
        private LocalDateTime departureTime;
        private List<RouteRequirement> routes;
    }

    @Data
    public static class RouteRequirement {
        private String sourceWarehouseCode;
        private String targetWarehouseCode;
        private String urgencyLevel;
    }

    @Data
    public static class PreviewQuery {
        private String factoryCode;
        private String sourceWarehouseCode;
        private String targetWarehouseCode;
        private String urgencyLevel;
        private LocalDateTime departureTime;
    }

    @Data
    @Accessors(chain = true)
    public static class RoutePlan {
        private String routeKey;
        private Long policyId;
        private String policyCode;
        private String policyName;
        private String sourceWarehouseCode;
        private String targetWarehouseCode;
        private String transportMode;
        /**
         * 实际命中的策略等级；紧急请求在没有专用策略时可保守回落NORMAL。
         */
        private String policyUrgencyLevel;
        private String urgencyLevel;
        private Integer leadTimeMinutes;
        private Integer transferLeadHours;
        private String calendarMode;
        private LocalDateTime departureTime;
        private LocalDateTime estimatedArrivalTime;
        private String policyFingerprint;
    }
}
