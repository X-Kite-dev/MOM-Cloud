package com.kitex.mom.iot.parameter;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 本版本仅提供明确标记的模拟通道，客户端不能提交目标参数或设备回执正文。 */
public final class IotParameterCommandDTO {
    private IotParameterCommandDTO() {}

    @Data
    @Accessors(chain = true)
    public static class Prepare {
        private String requestId;
        private String factoryCode;
        private String taskCode;
        private String arrangeCode;
        private String deviceCode;
        private String firmwareVersion;
        private String mode;
        private String scenario;
        private String reason;

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("参数下发不接受额外字段或替换参数正文");
        }
    }

    public record Detail(
            IotParameterCommand command,
            List<IotParameterReceipt> receipts,
            String simulatorOutcome,
            Integer simulatedExecutionCount) {}

    public record Readback(String value, String unit) {}
}
