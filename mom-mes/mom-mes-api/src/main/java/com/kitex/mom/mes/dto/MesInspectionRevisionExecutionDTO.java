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
 * 修订准入专用签名运输；与普通检验事件写密钥和签名域分别管理。
 */
@Data
@Accessors(chain = true)
public class MesInspectionRevisionExecutionDTO {
    private String operation;
    private Long requestId;
    private Long tenantId;
    private String factoryCode;
    private String intent;
    private String intentFingerprint;
    private String outcome;
    private String outcomeFingerprint;
    private Long issuedAt;
    private String nonce;
    private String signature;

    public String sign(String secret) {
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("修订准入专用写密钥未配置或不足32字符");
        try {
            var text = new StringBuilder("MOM-QMS-MES-INSPECTION-REVISION-EXECUTION-V1");
            for (Object value :
                    new Object[]{
                            operation,
                            requestId,
                            tenantId,
                            factoryCode,
                            intent,
                            intentFingerprint,
                            outcome,
                            outcomeFingerprint,
                            issuedAt,
                            nonce
                    }) {
                String s = Objects.toString(value, "");
                text.append('|').append(s.length()).append(':').append(s);
            }
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("修订准入签名不可用", e);
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
