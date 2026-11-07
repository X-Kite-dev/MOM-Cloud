package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.kitex.common.core.exception.CheckedException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * 现场厂商证明的有限RS256验签器：固定配置公钥，不读取JWT远端地址或动态算法。
 * 仅返回必要身份/设备及可选卡摘要，令牌及签名不进入持久账与日志。
 */
@Component
public class MesFieldIdentityProofVerifier {
	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

	/**
	 * 验证当前组织信任、公钥、有效期和完整挑战绑定；任一缺失均拒绝。
	 *
	 * @param proof     厂商签名JWT，仅本方法内使用
	 * @param trust     当前实际组织唯一配置
	 * @param challenge 当前本人原挑战账
	 * @return 已验签的必要身份与设备，非交接业务结果
	 */
	public Evidence verify(String proof, MesFieldIdentityProperties.Trust trust, Map<String, Object> challenge) {
		try {
			require(proof != null && proof.length() <= 16384, "须提供有效厂商签名证明");
			String[] parts = proof.split("\\.", -1);
			require(parts.length == 3 && java.util.Arrays.stream(parts).allMatch(p -> p.matches("[A-Za-z0-9_-]+")),
					"厂商证明格式无效");
			JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
			require("RS256".equals(text(header, "alg")) && Objects.equals(trust.getKeyId(), text(header, "kid")),
					"证明算法或公钥标识不匹配");
			require(!header.has("crit") && !header.has("jku") && !header.has("jwk") && !header.has("x5u"),
					"不接受动态密钥或扩展证明头");
			String pem = trust.getPublicKeyPem().replace("-----BEGIN PUBLIC KEY-----", "")
					.replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
			RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
					.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
			require(key.getModulus().bitLength() >= 2048, "厂商公钥至少2048位");
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(key);
			verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			require(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "厂商证明签名不成立");
			JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
			long now = Instant.now().getEpochSecond();
			long issued = number(claims, "iat"), before = number(claims, "nbf"), expires = number(claims, "exp");
			require(issued <= now && before <= now && expires > now && expires - issued <= 180 && expires > issued &&
					before >= issued, "厂商证明已过期、未生效或有效期过长");
			require(Objects.equals(trust.getIssuer(), text(claims, "iss")) &&
					Objects.equals(trust.getAudience(), text(claims, "aud")), "签名者或应用用途不匹配");
			require("HANDOVER".equals(text(claims, "purpose")), "厂商证明不是班次交接用途");
			Map<String, String> bindings = Map.of("challenge_id", "challenge_id", "tenant_id", "tenant_id",
					"factory_code", "factory_code",
					"sub", "actor_id", "action", "action", "request_id", "request_id", "command_sha256",
					"command_sha256", "mode", "mode");
			for (var binding : bindings.entrySet()) {
				require(Objects.equals(text(claims, binding.getKey()),
						String.valueOf(challenge.get(binding.getValue()))), "厂商证明与当前本人、组织或原命令不匹配");
			}
			String device = text(claims, "device_id");
			require(trust.getDeviceIds().contains(device), "设备不在当前租户工厂信任范围");
			String cardSha = claims.has("card_sha256") ? text(claims, "card_sha256") : null;
			require(cardSha == null || cardSha.matches("[a-f0-9]{64}"), "卡摘要格式无效");
			return new Evidence(text(claims, "sub"), device, cardSha);
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			// 不携带异常cause，防止解析器将令牌片段带入通用异常日志。
			throw new CheckedException("厂商证明或公钥配置无法核验");
		}
	}

	/**
	 * 计算不可逆SHA-256配置或命令指纹，不用于卡号授权。
	 */
	public static String sha256(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("SHA-256不可用");
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		require(value != null && value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 200,
				"厂商证明缺少必要文本字段");
		return value.asText();
	}

	private static long number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0,
				"厂商证明缺少有效UTC秒时间");
		return value.longValue();
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 验签后允许持久化的最小结果。
	 *
	 * @param subject    已登录本人ID
	 * @param deviceId   已配置受信任设备
	 * @param cardSha256 可空卡摘要，仅审计不作为权限
	 */
	public record Evidence(String subject, String deviceId, String cardSha256) {
	}
}
