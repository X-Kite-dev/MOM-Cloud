package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 标签固定协议的原文编码、摘要与无损主键校验；失败均拒绝而不生成替代值。
 */
final class MesProductionLabelCodec {
	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

	private MesProductionLabelCodec() {
	}

	static void require(boolean ok, String reason) {
		if (!ok) {
			throw new CheckedException(reason);
		}
	}

	static String write(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception e) {
			throw new CheckedException("标签原件无法编码", e);
		}
	}

	static <T> T read(String raw, Class<T> type) {
		try {
			return JSON.readValue(raw, type);
		} catch (Exception e) {
			throw new CheckedException("标签原件无法读取", e);
		}
	}

	static String sha(String raw) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new CheckedException("标签原件指纹无法核验", e);
		}
	}

	static Long id(String raw) {
		require(raw != null && raw.matches("[1-9][0-9]{0,18}"), "标签主键必须为精确正整数文本");
		try {
			return Long.valueOf(raw);
		} catch (NumberFormatException e) {
			throw new CheckedException("标签主键超出范围");
		}
	}

	static void hash(String raw) {
		require(raw != null && raw.matches("[a-f0-9]{64}"), "标签原件或模板指纹无效");
	}

	static void request(String raw) {
		require(raw != null && raw.matches("[A-Za-z0-9_-]{16,80}"), "请保留合法标签原请求号");
	}

	static void text(String raw, String name) {
		require(raw != null && !raw.isBlank() && raw.equals(raw.trim()) && raw.length() <= 200 &&
				raw.chars().noneMatch(Character::isISOControl), name + "缺失或格式无效");
	}
}
