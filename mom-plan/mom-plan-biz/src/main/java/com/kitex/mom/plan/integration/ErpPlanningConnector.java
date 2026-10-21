package com.kitex.mom.plan.integration;

import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;

/**
 * 已登记 ERP 适配器的稳定 SPI。
 *
 * <p>实现类由部署包显式提供并按 ERP 系统编码注册，禁止从数据库读取任意 URL 动态发送。</p>
 */
public interface ErpPlanningConnector {
    boolean supports(String erpSystemCode);

    SendResult send(PlnErpPostingEnvelope envelope);

    /** 连接器必须明确区分成功、可重试和业务拒绝。 */
    record SendResult(boolean accepted, boolean retryable, String receiptCode,
                      String receiptPayload, String message) {
        public static SendResult accepted(String code, String payload) {
            return new SendResult(true, false, code, payload, "ERP已确认接收");
        }

        public static SendResult retryable(String message) {
            return new SendResult(false, true, null, null, message);
        }

        public static SendResult rejected(String code, String message) {
            return new SendResult(false, false, code, null, message);
        }
    }
}
