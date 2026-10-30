package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 专用于QMS普通检验事件冻结原件写入；使用独立写密钥，不能复用回执只读签名。
 */
@Data
@Accessors(chain = true)
public class MesInspectionEventDeliveryDTO {
    private Long eventId;
    private Long tenantId;
    private String factoryCode;
    private String payload;
    private String fingerprint;
    private Long issuedAt;
    private String nonce;
    private String signature;

    public String sign(String secret) {
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("普通检验事件投递专用写密钥未配置或不足32字符");
        try {
            StringBuilder text = new StringBuilder("MOM-QMS-MES-INSPECTION-EVENT-RECEIVE-V1");
            for (Object value :
                    new Object[]{
                            eventId, tenantId, factoryCode, payload, fingerprint, issuedAt, nonce
                    }) {
                String s = Objects.toString(value, "");
                text.append('|').append(s.length()).append(':').append(s);
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("普通检验事件投递签名不可用", e);
        }
    }

    public boolean signatureMatches(String secret) {
        return secret != null
                && secret.length() >= 32
                && signature != null
                && signature.matches("[0-9a-f]{64}")
                && MessageDigest.isEqual(
                sign(secret).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
