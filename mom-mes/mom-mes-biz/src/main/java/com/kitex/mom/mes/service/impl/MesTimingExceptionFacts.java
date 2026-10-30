package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.service.MesReportCommandService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 时间例外独立输入域；不改变原GOOD命令或NG来源协议，也不接受端侧许可布尔值。
 */
public final class MesTimingExceptionFacts {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MesTimingExceptionFacts() {
    }

    public static Context good(MesReportCommandService.CapturedCommand captured, BigDecimal quantity) {
        return new Context("GOOD", captured.requestId(), captured.actorId().toString(), captured.actorCode(),
                decimal(quantity), captured.version(), captured.snapshot(), captured.fingerprint());
    }

    public static Context ng(String requestId, Long actorId, String actorCode, BigDecimal quantity, String input) {
        return new Context("NG", requestId, actorId.toString(), actorCode, decimal(quantity),
                "MES_TIMING_NG_INPUT_V1", input, hash("MES_TIMING_NG_INPUT_V1:" + input));
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new CheckedException("时间例外正文无法序列化");
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new CheckedException("时间例外原文指纹计算失败");
        }
    }

    private static String decimal(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new CheckedException("时间例外须绑定原报工正数量");
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * 原号、实名、数量与完整输入共同绑定一次许可；GOOD/NG始终分域。
     */
    public record Context(String reportKind, String requestId, String actorId, String actorCode,
                          String quantity, String inputVersion, String inputSnapshot, String fingerprint) {
    }
}
