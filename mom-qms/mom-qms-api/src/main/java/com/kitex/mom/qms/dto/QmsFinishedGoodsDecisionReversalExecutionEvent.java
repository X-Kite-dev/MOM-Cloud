package com.kitex.mom.qms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * WMS完成成品实物红冲后发给QMS的永久执行事件。
 *
 * <p>事件只确认仓库已经执行此前消费的质量逆向授权，不改写原质检任务和检验结论。
 * 事件号和指纹用于跨服务幂等、响应丢失重查以及事后对账。</p>
 */
@Data
@Accessors(chain = true)
public class QmsFinishedGoodsDecisionReversalExecutionEvent {
    @NotBlank
    @Size(max = 180)
    private String eventId;
    @NotNull
    private Long reversalId;
    @NotNull
    private Long wmsCorrectionId;
    @NotBlank
    @Size(max = 180)
    private String wmsCorrectionRequestId;
    @NotNull
    private Long wmsNoticeId;
    @NotBlank
    @Size(min = 64, max = 64)
    private String sourceQualityEvidenceFingerprint;
    @NotBlank
    @Size(min = 64, max = 64)
    private String qmsEvidenceFingerprint;
    @NotNull
    private BigDecimal reverseQuantity;
    @NotBlank
    @Size(max = 100)
    private String factoryCode;
    @NotNull
    private LocalDateTime executedTime;
    @NotBlank
    @Size(min = 64, max = 64)
    private String fingerprint;

    /**
     * 以稳定字段顺序计算事件事实摘要，不包含传输状态。
     */
    public String calculateFingerprint() {
        String canonical = String.join("|",
                value(eventId), value(reversalId), value(wmsCorrectionId), value(wmsCorrectionRequestId),
                value(wmsNoticeId), value(sourceQualityEvidenceFingerprint), value(qmsEvidenceFingerprint),
                reverseQuantity == null ? "" : reverseQuantity.stripTrailingZeros().toPlainString(),
                value(factoryCode), value(executedTime));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算QMS质量逆向执行事件指纹", exception);
        }
    }

    private String value(Object value) {
        return Objects.toString(value, "");
    }

    /**
     * QMS永久保存后返回的可重查回执。
     */
    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String eventId;
        private String fingerprint;
        private Long reversalId;
        private Long wmsCorrectionId;
        private String status;
        private LocalDateTime acceptedTime;

        public boolean matches(QmsFinishedGoodsDecisionReversalExecutionEvent event) {
            return event != null && Objects.equals(eventId, event.getEventId())
                    && Objects.equals(fingerprint, event.getFingerprint())
                    && Objects.equals(reversalId, event.getReversalId())
                    && Objects.equals(wmsCorrectionId, event.getWmsCorrectionId())
                    && "ACCEPTED".equals(status);
        }
    }
}
