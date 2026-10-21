package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 生产发料线边配送状态迁移命令。
 */
public final class WmsLineDeliveryCommand {
    private WmsLineDeliveryCommand() {
    }

    @Data
    @Accessors(chain = true)
    public static class Dispatch {
        private String requestId;
        private String targetSiteCode;
        private String priority;
        private LocalDateTime requiredArrivalTime;
        private String reason;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Accept {
        private String requestId;
        private String transportMode;
        private String vehicleCode;
        private String containerCode;
        private String sealNo;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Transit {
        private String requestId;
        private String evidence;
    }

    @Data
    @Accessors(chain = true)
    public static class Arrival {
        private String requestId;
        private String evidence;
        private List<ArrivalLine> lines;
    }

    @Data
    @Accessors(chain = true)
    public static class ArrivalLine {
        private Long outBillDetailId;
        private BigDecimal deliveredNum;
        private BigDecimal shortNum;
        private BigDecimal damagedNum;
        private String responsibility;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Resolve {
        private String requestId;
        private String summary;
        private String evidence;
        private List<ResolveLine> lines;
    }

    @Data
    @Accessors(chain = true)
    public static class ResolveLine {
        private Long detailId;
        private BigDecimal recoveredNum;
        private BigDecimal returnedNum;
        private BigDecimal lossNum;
        private String resolutionReference;
        private String remark;
    }
}
