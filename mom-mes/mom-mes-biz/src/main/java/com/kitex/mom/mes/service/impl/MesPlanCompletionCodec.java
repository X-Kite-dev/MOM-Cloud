package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesPlanCompletionDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 仅处理本域不可变原件；摘要始终来自保存的原文，不重排HTTP解码内容。
 */
public final class MesPlanCompletionCodec {

    private MesPlanCompletionCodec() {
    }

    public static String hash(String json) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("完工通知摘要生成失败");
        }
    }

    public static String encode(ObjectMapper mapper, Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            require(json.length() <= 1_000_000, "完工通知原文超过安全上限");
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CheckedException("完工通知原文生成失败");
        }
    }

    public static MesPlanCompletionDTO.Event decode(ObjectMapper mapper, String json) {
        require(json != null && json.length() <= 1_000_000, "完工通知缺少有界原文");
        try {
            var event =
                    mapper.copy()
                            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .enable(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(json, MesPlanCompletionDTO.Event.class);
            require(
                    event != null && Integer.valueOf(1).equals(event.getSchemaVersion()),
                    "未知完工通知模型");
            return event;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CheckedException("完工通知原文不可读取");
        }
    }

    static void require(boolean allowed, String message) {
        if (!allowed) throw new CheckedException(message);
    }
}
