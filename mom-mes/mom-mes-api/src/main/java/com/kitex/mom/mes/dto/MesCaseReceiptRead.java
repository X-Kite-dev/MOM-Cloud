package com.kitex.mom.mes.dto;

import lombok.Data;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 仅用于按精确决策读取已提交实物回执，签名不能用于执行/验证命令。
 */
@Data
public class MesCaseReceiptRead {
    private Long tenantId;
    private String factoryCode;
    private Long caseId;
    private Long ncrId;
    private Long detailId;
    private String decisionKey;
    private Long issuedAt;
    private String nonce;
    private String signature;

    public String sign(String secret) {
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("MES回执只读签名密钥未配置或不足32字符");
        try {
            // 长度前缀避免工厂/决策编码中的分隔符引起拼接歧义。
            StringBuilder text = new StringBuilder("MOM-MES-RECEIPT-READ-V1");
            for (Object value : new Object[]{tenantId, factoryCode, caseId, ncrId, detailId, decisionKey, issuedAt, nonce}) {
                String s = java.util.Objects.toString(value, "");
                text.append('|').append(s.length()).append(':').append(s);
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("回执只读签名不可用", e);
        }
    }

    public boolean signatureMatches(String secret) {
        return signature != null && signature.matches("[0-9a-f]{64}")
                && MessageDigest.isEqual(sign(secret).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }
}
