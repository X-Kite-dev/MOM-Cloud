package com.kitex.mom.pms.dto;

import cn.hutool.json.JSONUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * WMS 实物退供完成后回写 PMS 的永久事件。
 */
@Data
@Accessors(chain = true)
@Schema(description = "采购退供实物出库事件")
public class PmsSupplierReturnEvent {
    private String eventId;
    private Long returnId;
    private String returnNo;
    private Long wmsNoticeId;
    private Long wmsOutBillId;
    private String wmsOutBillNo;
    private String factoryCode;
    private LocalDateTime completedTime;
    private List<Line> lines;
    private String fingerprint;

    public String calculateFingerprint() {
        try {
            // Arrays.asList 允许业务可选字段为空，指纹仍能稳定覆盖完整载荷。
            String value = JSONUtil.toJsonStr(Arrays.asList(returnId, returnNo, wmsNoticeId, wmsOutBillId,
                    wmsOutBillNo, factoryCode, completedTime, lines));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("退供事件指纹生成失败", exception);
        }
    }

    @Data
    @Accessors(chain = true)
    public static class Line {
        private Long returnDetailId;
        private Long orderDetailId;
        private String planNo;
        private Long liveStockId;
        private String materialCode;
        private String batchNo;
        private BigDecimal actualReturnQuantity;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String eventId;
        private Long returnId;
        private String fingerprint;
        private String status;
        private LocalDateTime receivedTime;
    }
}
