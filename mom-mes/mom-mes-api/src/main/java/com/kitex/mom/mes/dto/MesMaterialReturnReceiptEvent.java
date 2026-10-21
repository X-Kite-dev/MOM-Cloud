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
 * WMS生产退料实收事件。
 *
 * <p>事件只描述WMS已经记账的累计实收及已受控申报的差异，不以通知量冒充实收量。</p>
 */
@Data
@Accessors(chain = true)
public class MesMaterialReturnReceiptEvent {

    private Integer version;
    private String eventId;
    private String fingerprint;
    private Long returnRequestId;
    private Long wmsNoticeId;
    private Long wmsInBillId;
    private String wmsInBillNo;
    private String factoryCode;
    private BigDecimal expectedQuantity;
    private BigDecimal receiptQuantity;
    private BigDecimal cumulativeReceivedQuantity;
    private BigDecimal shortageQuantity;
    private BigDecimal overageQuantity;
    private Boolean finalReceipt;
    private String receiptStatus;
    private LocalDateTime receiptTime;

    public String calculateFingerprint() {
        if (!Integer.valueOf(1).equals(version) || eventId == null || eventId.isBlank()
                || returnRequestId == null || wmsNoticeId == null || factoryCode == null || factoryCode.isBlank()
                || expectedQuantity == null || expectedQuantity.signum() <= 0
                || receiptQuantity == null || receiptQuantity.signum() < 0
                || cumulativeReceivedQuantity == null || cumulativeReceivedQuantity.signum() < 0
                || shortageQuantity == null || shortageQuantity.signum() < 0
                || overageQuantity == null || overageQuantity.signum() < 0
                || finalReceipt == null || receiptStatus == null || receiptStatus.isBlank() || receiptTime == null) {
            throw new CheckedException("生产退料实收事件来源或数量不完整");
        }
        if (cumulativeReceivedQuantity.compareTo(expectedQuantity) > 0
                || shortageQuantity.compareTo(expectedQuantity.subtract(cumulativeReceivedQuantity)) != 0) {
            throw new CheckedException("生产退料实收事件数量不守恒");
        }
        try {
            String facts = JSONUtil.toJsonStr(Arrays.asList(version, eventId, returnRequestId, wmsNoticeId,
                    wmsInBillId, wmsInBillNo, factoryCode, number(expectedQuantity), number(receiptQuantity),
                    number(cumulativeReceivedQuantity), number(shortageQuantity), number(overageQuantity),
                    finalReceipt, receiptStatus, receiptTime));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String eventId;
        private String fingerprint;
        private Long returnRequestId;
        private Long wmsNoticeId;
        private String factoryCode;
        private BigDecimal cumulativeReceivedQuantity;
        private BigDecimal shortageQuantity;
        private BigDecimal overageQuantity;
        private String receiptStatus;
        private String readinessFeedbackStatus;
        private LocalDateTime acceptedTime;

        public boolean matches(MesMaterialReturnReceiptEvent event) {
            return event != null && Objects.equals(eventId, event.getEventId())
                    && Objects.equals(fingerprint, event.getFingerprint())
                    && Objects.equals(returnRequestId, event.getReturnRequestId())
                    && Objects.equals(wmsNoticeId, event.getWmsNoticeId())
                    && Objects.equals(factoryCode, event.getFactoryCode());
        }
    }
}
