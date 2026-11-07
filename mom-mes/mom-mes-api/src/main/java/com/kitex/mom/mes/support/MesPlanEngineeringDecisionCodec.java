package com.kitex.mom.mes.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesPlanEngineeringDecisionDTO.Receipt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * 永久MES决策回执的固定字符串规范，与PLAN API无依赖。两域按相同原文和SHA核事实，
 * 不将客户端成功标记、HTTP成功或空读当作终态；未知结果始终沿原PEP恢复。
 */
public final class MesPlanEngineeringDecisionCodec {

	private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS");

	private MesPlanEngineeringDecisionCodec() {
	}

	/**
	 * 固定24字段，不含自身receiptFingerprint；允许的null仍显式保留。
	 */
	public static String canonical(Receipt row) {
		require(row != null, "MES决策回执缺失");
		var value = new LinkedHashMap<String, String>();
		value.put("schemaVersion", row.schemaVersion());
		value.put("id", row.id());
		value.put("tenantId", row.tenantId());
		value.put("factoryCode", row.factoryCode());
		value.put("preparationId", row.preparationId());
		value.put("requestId", row.requestId());
		value.put("authorityFingerprint", row.authorityFingerprint());
		value.put("taskId", row.taskId());
		value.put("taskCode", row.taskCode());
		value.put("productCode", row.productCode());
		value.put("sourceReleaseId", row.sourceReleaseId());
		value.put("sourceContentHash", row.sourceContentHash());
		value.put("targetReleaseId", row.targetReleaseId());
		value.put("targetContentHash", row.targetContentHash());
		value.put("decision", row.decision());
		value.put("actorId", row.actorId());
		value.put("decidedAt", row.decidedAt());
		value.put("reason", row.reason());
		value.put("processId", row.processId());
		value.put("routeNodeCode", row.routeNodeCode());
		value.put("processCode", row.processCode());
		value.put("sourceFingerprint", row.sourceFingerprint());
		value.put("laborFingerprint", row.laborFingerprint());
		value.put("commandFingerprint", row.commandFingerprint());
		return json(value);
	}

	/**
	 * 从保存的规范原文恢复回执；重复字段、额外字段、非规范字节或SHA不符均拒绝。
	 */
	public static Receipt fromSnapshot(String raw, String fingerprint) {
		try {
			require(raw != null && hash(raw).equals(fingerprint), "MES永久回执原文摘要不一致");
			var value = JSON.readTree(raw);
			require(value instanceof ObjectNode && !value.has("receiptFingerprint"), "MES规范回执字段异常");
			((ObjectNode) value).put("receiptFingerprint", fingerprint);
			Receipt receipt = JSON.treeToValue(value, Receipt.class);
			validate(receipt);
			require(raw.equals(canonical(receipt)), "MES永久回执不符合固定规范，不能改写后接受");
			return receipt;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("MES永久决策原件无法完整解析", failure);
		}
	}

	/**
	 * 只接受完整实际终态；APPLIED含节点和原件摘要，CANCELLED不得混入采用事实。
	 */
	public static void validate(Receipt row) {
		require(row != null && "MES_PLAN_ENGINEERING_DECISION_V1".equals(row.schemaVersion())
				&& Set.of("APPLIED", "CANCELLED").contains(row.decision()), "MES永久决策版本或终态无效");
		for (String value : new String[]{row.id(), row.tenantId(), row.preparationId(), row.taskId(), row.sourceReleaseId(),
				row.targetReleaseId(), row.actorId()}) {
			id(value);
		}
		for (String value : new String[]{row.authorityFingerprint(), row.sourceContentHash(), row.targetContentHash(),
				row.commandFingerprint(), row.receiptFingerprint()}) {
			hashValue(value);
		}
		text(row.factoryCode(), 100);
		text(row.taskCode(), 100);
		text(row.productCode(), 100);
		text(row.reason(), 1000);
		require(row.requestId() != null && row.requestId().length() <= 100
				&& row.requestId().matches("PEP:[1-9][0-9]{0,18}:[A-Za-z0-9_.:-]{8,60}"), "MES原PEP号无效");
		try {
			require(row.decidedAt() != null &&
							TIME.format(LocalDateTime.parse(row.decidedAt(), TIME)).equals(row.decidedAt()),
					"MES决策时间不是精确ISO原值");
		} catch (Exception failure) {
			throw new CheckedException("MES决策时间不可完整核验", failure);
		}
		if ("APPLIED".equals(row.decision())) {
			id(row.processId());
			text(row.processCode(), 100);
			require(row.routeNodeCode() != null && row.routeNodeCode().matches("RN-[1-9][0-9]{0,18}"),
					"采用稳定节点身份无效");
			hashValue(row.sourceFingerprint());
			hashValue(row.laborFingerprint());
		} else {
			require(row.processId() == null && row.routeNodeCode() == null && row.processCode() == null
					&& row.sourceFingerprint() == null && row.laborFingerprint() == null, "撤销回执不能带采用事实");
		}
		require(hash(canonical(row)).equals(row.receiptFingerprint()), "MES决策规范回执SHA不一致");
	}

	/**
	 * 精确UTF8原文SHA256，数量/主键不转浮点。
	 */
	public static String hash(String raw) {
		try {
			require(raw != null, "原件正文缺失");
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("MES原文摘要计算失败", failure);
		}
	}

	private static String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception failure) {
			throw new CheckedException("MES决策规范化失败", failure);
		}
	}

	private static void id(String value) {
		try {
			require(value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0, "MES回执原ID无效");
		} catch (NumberFormatException failure) {
			throw new CheckedException("MES回执原ID超过Long范围", failure);
		}
	}

	private static void hashValue(String value) {
		require(value != null && value.matches("[0-9a-f]{64}"), "MES回执原件SHA格式无效");
	}

	private static void text(String value, int maximum) {
		require(value != null && !value.isBlank() && value.length() <= maximum, "MES回执身份或依据缺失");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
