package com.kitex.mom.mes.dto;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * WMS成品实物红冲事件。
 *
 * <p>V1表示历史整笔撤销；V2表示WMS已经冻结精确库存行和数量的批次/SN部分撤销。
 * MES不猜测实物身份，只按原入库报工来源的剩余净额稳定FIFO释放，并为每个事件保存
 * 不可变反向明细。事件由WMS源事务冻结，通过Outbox重试并由MES永久回执确认。</p>
 */
@Data
@Accessors(chain = true)
public class MesFinishedGoodsReceiptReverseEvent {
    private Integer version;
    private String eventId;
    private String fingerprint;
    private Long correctionId;
    private Long wmsNoticeId;
    private String sourceMesInboundRequestId;
    private String sourceRequestFingerprint;
    private String evidenceFingerprint;
    /**
     * FULL_RECEIPT/PARTIAL_BATCH/SN_EXACT/MIXED_EXACT；V1事件可为空。
     */
    private String reversalMode;
    private BigDecimal reverseQuantity;
    private String reason;
    private String applicantCode;
    private String reviewerCode;
    private String factoryCode;
    private LocalDateTime executedTime;

    public String calculateFingerprint() {
        if (!(Integer.valueOf(1).equals(version) || Integer.valueOf(2).equals(version))
                || blank(eventId) || correctionId == null
                || wmsNoticeId == null || blank(sourceMesInboundRequestId)
                || blank(sourceRequestFingerprint) || sourceRequestFingerprint.length() != 64
                || blank(evidenceFingerprint) || evidenceFingerprint.length() != 64
                || reverseQuantity == null || reverseQuantity.signum() <= 0 || blank(reason)
                || blank(applicantCode) || blank(reviewerCode) || Objects.equals(applicantCode, reviewerCode)
                || blank(factoryCode) || executedTime == null) {
            throw new CheckedException("成品实物红冲事件来源、数量、岗位或指纹不完整");
        }
        if (Integer.valueOf(2).equals(version)
                && !("FULL_RECEIPT".equals(reversalMode) || "PARTIAL_BATCH".equals(reversalMode)
                || "SN_EXACT".equals(reversalMode) || "MIXED_EXACT".equals(reversalMode))) {
            throw new CheckedException("V2成品实物红冲事件缺少有效红冲模式");
        }
        try {
            Object factsValue = Integer.valueOf(1).equals(version)
                    ? Arrays.asList(version, eventId, correctionId, wmsNoticeId,
                    sourceMesInboundRequestId, sourceRequestFingerprint, evidenceFingerprint,
                    reverseQuantity.stripTrailingZeros().toPlainString(), reason, applicantCode,
                    reviewerCode, factoryCode, executedTime)
                    : Arrays.asList(version, eventId, correctionId, wmsNoticeId,
                    sourceMesInboundRequestId, sourceRequestFingerprint, evidenceFingerprint,
                    reversalMode, reverseQuantity.stripTrailingZeros().toPlainString(), reason, applicantCode,
                    reviewerCode, factoryCode, executedTime);
            String facts = JSONUtil.toJsonStr(factsValue);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String eventId;
        private String fingerprint;
        private Long correctionId;
        private Long wmsNoticeId;
        private String sourceMesInboundRequestId;
        private Long mesReceiptId;
        private Long mesTaskId;
        private String mesTaskCode;
        private BigDecimal reversedQuantity;
        private String factoryCode;
        private LocalDateTime acceptedTime;

        public boolean matches(MesFinishedGoodsReceiptReverseEvent event) {
            return event != null && Objects.equals(eventId, event.getEventId())
                    && Objects.equals(fingerprint, event.getFingerprint())
                    && Objects.equals(correctionId, event.getCorrectionId())
                    && Objects.equals(wmsNoticeId, event.getWmsNoticeId())
                    && Objects.equals(sourceMesInboundRequestId, event.getSourceMesInboundRequestId())
                    && Objects.equals(factoryCode, event.getFactoryCode());
        }
    }
}
