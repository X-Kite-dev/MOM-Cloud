package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 移动端弱网报工重放协议。
 */
public final class MesOfflineReportReplayDTO {

    private MesOfflineReportReplayDTO() {
    }

    @Data
    @Schema(description = "单条弱网报工重放命令")
    public static class Command {
        @Schema(description = "服务端签发的授权凭证号")
        private String permitNo;
        @Schema(description = "服务端签发时返回的随机令牌")
        private String permitToken;
        @Schema(description = "设备本地严格递增序号")
        private Long localSequence;
        @Schema(description = "现场实际采集时间")
        private LocalDateTime capturedAt;
        @Schema(description = "原始移动报工命令；requestId在所有重试中必须保持不变")
        private MesTaskReportVO report;
    }

    @Data
    @Schema(description = "弱网报工批量重放请求")
    public static class BatchRequest {
        @Schema(description = "待重放命令；单批最多50条")
        private List<Command> commands;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "单条弱网报工重放结果")
    public static class Result {
        private String requestId;
        private String permitNo;
        private Long localSequence;
        private String status;
        private String message;
        private Long reportId;
        private LocalDateTime acceptedAt;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "弱网报工批量重放结果")
    public static class BatchResult {
        private Integer totalCount;
        private Integer acceptedCount;
        private Integer rejectedCount;
        private List<Result> results;
    }
}
