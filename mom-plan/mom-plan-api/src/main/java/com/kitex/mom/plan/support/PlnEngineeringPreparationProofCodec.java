package com.kitex.mom.plan.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlnEngineeringPreparationDTO;
import com.kitex.mom.plan.dto.PlnEngineeringResolutionDTO.Certificate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/**
 * 跨域准备凭据的固定字符串规范。只使用不可变原准备身份，终结状态变化不改变authority摘要。
 * 原七字段命令继续使用V1，不能因新增采用合同重算历史请求指纹。
 */
public final class PlnEngineeringPreparationProofCodec {

	private static final ObjectMapper JSON = new ObjectMapper();

	private PlnEngineeringPreparationProofCodec() {
	}

	/**
	 * 固定V1命令顺序与字符串类型，与原准备服务逐字一致。
	 */
	public static String commandCanonical(PlnEngineeringPreparationDTO.Command command) {
		require(command != null, "原准备命令缺失");
		var value = new LinkedHashMap<String, String>();
		value.put("schemaVersion", "PLAN_ENGINEERING_PREPARE_V1");
		value.put("requestId", command.requestId());
		value.put("taskCode", command.taskCode());
		value.put("targetReleaseId", command.targetReleaseId());
		value.put("targetContentHash", command.targetContentHash());
		value.put("expectedObservationFingerprint", command.expectedObservationFingerprint());
		value.put("reason", command.reason());
		return json(value);
	}

	/**
	 * 生成20个字符串字段的不可变authority正文；不接受缺工单身份、截断范围或未受理墓碑。
	 */
	public static String canonical(Certificate certificate) {
		require(certificate != null && certificate.preparation() != null, "完整原准备凭据缺失");
		var row = certificate.preparation();
		var command = row.original();
		require("PLAN_ENGINEERING_PREPARE_V2".equals(row.schemaVersion()) && command != null,
				"准备凭据版本不支持采用协同");
		id(row.id());
		id(row.tenantId());
		id(row.applicantId());
		id(certificate.taskId());
		id(row.sourceReleaseId());
		id(command.targetReleaseId());
		text(row.factoryCode(), 100);
		text(command.taskCode(), 100);
		text(certificate.productCode(), 100);
		text(command.reason(), 1000);
		require(command.requestId() != null && command.requestId().length() <= 100
						&& command.requestId().matches("PEP:" + row.applicantId() + ":[A-Za-z0-9_.:-]{8,60}"),
				"原准备号与实际申请人不一致");
		for (String value : new String[]{row.requestFingerprint(), row.sourceContentHash(), command.targetContentHash(),
				command.expectedObservationFingerprint(), row.observationSnapshotHash(), certificate.mappingFingerprint()}) {
			hashValue(value);
		}
		require(hash(commandCanonical(command)).equals(row.requestFingerprint()) && row.preparedAt() != null
						&& row.preparedAt().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{9}")
						&& row.scopeCount() != null && row.scopeCount() > 0 && row.scopeCount() <= 15000,
				"原准备正文、真实准备时间或全量范围不可核验");
		var value = new LinkedHashMap<String, String>();
		value.put("schemaVersion", "PLAN_ENGINEERING_PREPARATION_AUTHORITY_V1");
		value.put("preparationId", row.id());
		value.put("requestId", command.requestId());
		value.put("requestFingerprint", row.requestFingerprint());
		value.put("canonicalRequest", commandCanonical(command));
		value.put("tenantId", row.tenantId());
		value.put("factoryCode", row.factoryCode());
		value.put("taskId", certificate.taskId());
		value.put("taskCode", command.taskCode());
		value.put("productCode", certificate.productCode());
		value.put("sourceReleaseId", row.sourceReleaseId());
		value.put("sourceContentHash", row.sourceContentHash());
		value.put("targetReleaseId", command.targetReleaseId());
		value.put("targetContentHash", command.targetContentHash());
		value.put("mappingFingerprint", certificate.mappingFingerprint());
		value.put("observationFingerprint", command.expectedObservationFingerprint());
		value.put("observationSnapshotHash", row.observationSnapshotHash());
		value.put("applicantId", row.applicantId());
		value.put("preparedAt", row.preparedAt());
		value.put("scopeCount", row.scopeCount().toString());
		return json(value);
	}

	/**
	 * 核传输凭据与固定规范正文SHA，不将HTTP成功当原准备证明。
	 */
	public static void validate(Certificate certificate) {
		require(certificate != null && hash(canonical(certificate)).equals(certificate.authorityFingerprint()),
				"准备权威凭据原文指纹不一致");
	}

	/**
	 * 精确UTF8原文SHA，不进行JSON重排、空白修剪或数值转换。
	 */
	public static String hash(String raw) {
		try {
			require(raw != null, "原件正文缺失");
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("原件摘要计算失败", failure);
		}
	}

	private static String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception failure) {
			throw new CheckedException("准备凭据序列化失败", failure);
		}
	}

	private static void id(String value) {
		try {
			require(value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0,
					"准备原主键必须为精确正整数文本");
		} catch (NumberFormatException failure) {
			throw new CheckedException("准备原主键超过Long范围", failure);
		}
	}

	private static void hashValue(String value) {
		require(value != null && value.matches("[0-9a-f]{64}"), "准备原件摘要格式无效");
	}

	private static void text(String value, int maximum) {
		require(value != null && !value.isBlank() && value.length() <= maximum, "准备身份或依据文本不可核验");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
