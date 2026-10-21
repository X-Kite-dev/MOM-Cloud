package com.kitex.mom.mes.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kitex.common.core.exception.CheckedException;

import java.util.Set;

/**
 * 正式返工跨工单时间窗口。
 *
 * <p>来源实际工序由返工工单冻结，策略只描述从该真实来源的哪个事件开始计时。
 * 秒数始终使用十进制文本，避免浏览器、JavaScript或数据库之间发生大数和小数舍入。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MesReworkTimingPolicy(String version, String mode, String scope, String sourceEvent,
                                    String minSeconds, String maxSeconds,
                                    String lateExceptionSeconds) {

    public static final String VERSION = "MES_REWORK_TIMING_V1";
    public static final String NONE = "NONE";
    public static final String WINDOW = "WINDOW";
    private static final long MAX_SECONDS = 315_360_000L;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Long minimum() {
        return minSeconds == null ? null : Long.valueOf(minSeconds);
    }

    public Long maximum() {
        return maxSeconds == null ? null : Long.valueOf(maxSeconds);
    }

    public boolean enabled() {
        return WINDOW.equals(mode);
    }

    /** 严格解析已批准策略；空值不是“无限制”，新返工必须明确批准NONE。 */
    public static MesReworkTimingPolicy parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4096) {
            throw new CheckedException("返工时间策略缺失或超过4KiB");
        }
        try {
            MesReworkTimingPolicy policy = JSON.readValue(raw, MesReworkTimingPolicy.class);
            validate(policy);
            return policy;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("返工时间策略格式错误，请通过返工执行方案重新提交");
        }
    }

    /** 返回字段顺序固定的批准正文。 */
    public static String encode(MesReworkTimingPolicy policy) {
        validate(policy);
        try {
            return JSON.writeValueAsString(policy);
        } catch (Exception exception) {
            throw new CheckedException("返工时间策略序列化失败");
        }
    }

    private static void validate(MesReworkTimingPolicy policy) {
        require(policy != null && VERSION.equals(policy.version), "返工时间策略版本不支持");
        require(Set.of(NONE, WINDOW).contains(policy.mode), "返工时间策略须明确选择无限制或时间窗口");
        if (NONE.equals(policy.mode)) {
            require(policy.scope == null && policy.sourceEvent == null
                            && policy.minSeconds == null && policy.maxSeconds == null
                            && policy.lateExceptionSeconds == null,
                    "无限制返工策略不能夹带时间窗口字段");
            return;
        }
        require(Set.of("WORK_ORDER", "IDENTIFIER").contains(policy.scope),
                "返工时间窗口须选择整单或同标识口径");
        require(("WORK_ORDER".equals(policy.scope) && Set.of("START", "FINISH").contains(policy.sourceEvent))
                        || ("IDENTIFIER".equals(policy.scope) && "ACCEPTED_REPORT".equals(policy.sourceEvent)),
                "整单口径只能从原实际工序开工/完工计时，同标识只能从原有效合格报工计时");
        seconds(policy.minSeconds);
        seconds(policy.maxSeconds);
        seconds(policy.lateExceptionSeconds);
        require(policy.minSeconds != null || policy.maxSeconds != null, "返工时间窗口至少设置一个边界");
        require(policy.minimum() == null || policy.maximum() == null || policy.maximum() >= policy.minimum(),
                "返工最晚允许时间不能小于最少等待时间");
        require(policy.lateExceptionSeconds == null || (policy.maxSeconds != null
                        && Long.parseLong(policy.lateExceptionSeconds) > 0),
                "返工超时例外须同时设置最晚允许时间和大于0的附加上限");
    }

    private static void seconds(String value) {
        if (value == null) return;
        require(value.matches("0|[1-9][0-9]{0,8}") && Long.parseLong(value) <= MAX_SECONDS,
                "返工时间边界须为0—315360000整数秒文本");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
