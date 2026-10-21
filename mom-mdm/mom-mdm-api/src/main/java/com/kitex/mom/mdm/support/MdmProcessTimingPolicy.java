package com.kitex.mom.mdm.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 工序报工时间窗口契约。规则挂在目标工序上，来源可以是任意上游工序。
 * 秒数使用整数字符串，避免端侧数值舍入；null代表未设置该边界。
 * 不提供忽略错误、客户端放行或把未知事件当作当前时间的选项。
 */
public record MdmProcessTimingPolicy(String version, List<Rule> rules) {
    public static final String VERSION = "MOM_PROCESS_TIMING_V4";
    public static final String PAUSE_VERSION = "MOM_PROCESS_TIMING_V3";
    public static final String EXCEPTION_VERSION = "MOM_PROCESS_TIMING_V2";
    public static final String LEGACY_VERSION = "MOM_PROCESS_TIMING_V1";
    private static final long MAX_SECONDS = 315_360_000L;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * @param code 目标工序内唯一规则编码
     * @param name 现场提示使用的规则名称
     * @param sourceProcessCode 真实来源工序编码（不是工艺路线编码）
     * @param scope WORK_ORDER工单范围或IDENTIFIER同规范标识
     * @param sourceEvent START/FINISH或ACCEPTED_REPORT
     * @param minSeconds 最少等待秒数，含端点
     * @param maxSeconds 最多允许秒数，含端点
     * @param lateExceptionSeconds 允许独立审批的最大额外秒数，空表示禁止例外；不影响最短等待
     * @param clockMode 空/ELAPSED自然时间；TARGET_ARRANGE_ACTIVE只扣本次目标派工的明确业务暂停
     * @param sourceVisit 空保持首轮限制；ORIGINAL沿首轮来源，EFFECTIVE_VISIT按批准路线版本解析有效来源轮次
     */
    public record Rule(String code, String name, String sourceProcessCode, String scope,
                       String sourceEvent, String minSeconds, String maxSeconds,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String lateExceptionSeconds,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String clockMode,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String sourceVisit) {
        public Long minimum() { return minSeconds == null ? null : Long.valueOf(minSeconds); }
        public Long maximum() { return maxSeconds == null ? null : Long.valueOf(maxSeconds); }
        public boolean deductTargetPause() { return "TARGET_ARRANGE_ACTIVE".equals(clockMode); }
    }

    /** 严格解码。旧路线空字段表示未配置，不为旧工单推断新政策。 */
    public static MdmProcessTimingPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) return new MdmProcessTimingPolicy(VERSION, List.of());
        if (raw.length() > 32_768) throw new CheckedException("工序时间规则超出32Ki字符限制");
        try {
            var tree = JSON.readTree(raw);
            if (tree != null && tree.path("rules").isArray()) {
                for (var rule : tree.path("rules")) {
                    for (String field : List.of("code", "name", "sourceProcessCode", "scope", "sourceEvent", "minSeconds", "maxSeconds", "lateExceptionSeconds", "clockMode", "sourceVisit")) {
                        var value = rule.get(field);
                        require(value == null || value.isNull() || value.isTextual(), "时间规则字段必须为文本，秒数不能使用JSON数值");
                    }
                }
            }
            MdmProcessTimingPolicy policy = JSON.readValue(raw, MdmProcessTimingPolicy.class);
            require(policy != null && Set.of(VERSION, PAUSE_VERSION, EXCEPTION_VERSION, LEGACY_VERSION).contains(policy.version()), "工序时间规则版本不支持");
            require(policy.rules() != null && policy.rules().size() <= 20, "每道工序最多配置20条时间规则");
            Set<String> codes = new HashSet<>();
            for (Rule rule : policy.rules()) {
                require(rule != null, "时间规则不能为空");
                require(rule.code() != null && rule.code().matches("[A-Za-z0-9_-]{1,40}")
                        && codes.add(rule.code()), "时间规则编码必须唯一且为1—40位字母、数字、下划线或短横线");
                require(rule.name() != null && !rule.name().isBlank() && rule.name().length() <= 100,
                        "时间规则名称必须为1—100个字符");
                require(rule.sourceProcessCode() != null && !rule.sourceProcessCode().isBlank()
                        && rule.sourceProcessCode().length() <= 100, "时间规则必须选择来源工序");
                require(("WORK_ORDER".equals(rule.scope()) && Set.of("START", "FINISH").contains(rule.sourceEvent()))
                                || ("IDENTIFIER".equals(rule.scope()) && "ACCEPTED_REPORT".equals(rule.sourceEvent())),
                        "工单范围只能从实际开工/完工计时，同标识范围只能从有效合格报工接受时刻计时");
                checkSeconds(rule.minSeconds());
                checkSeconds(rule.maxSeconds());
                checkSeconds(rule.lateExceptionSeconds());
                require(rule.lateExceptionSeconds() == null || (Set.of(VERSION, PAUSE_VERSION, EXCEPTION_VERSION).contains(policy.version())
                                && rule.maxSeconds() != null && Long.parseLong(rule.lateExceptionSeconds()) > 0),
                        "超时例外须使用V2及以上规则，设置最长时限及大于0的额外审批上限");
                require(rule.clockMode() == null || (Set.of(VERSION, PAUSE_VERSION).contains(policy.version())
                                && Set.of("ELAPSED", "TARGET_ARRANGE_ACTIVE").contains(rule.clockMode())),
                        "计时口径须使用V3及以上规则明确选择自然时间或目标派工暂停扣除");
                require(rule.sourceVisit() == null || (VERSION.equals(policy.version())
                                && ("ORIGINAL".equals(rule.sourceVisit())
                                || ("IDENTIFIER".equals(rule.scope()) && "EFFECTIVE_VISIT".equals(rule.sourceVisit())))),
                        "来源轮次须使用V4明确选择首轮，或同标识批准路线有效轮次；工单开完工不是返工时刻");
                require(rule.minSeconds() != null || rule.maxSeconds() != null, "时间规则至少设置一个边界");
                require(rule.minimum() == null || rule.maximum() == null || rule.maximum() >= rule.minimum(),
                        "最晚允许时间不能小于最少等待时间");
            }
            return policy;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("时间规则格式错误，请通过工艺路线时间规则编辑器重新保存");
        }
    }

    /** 检查真实节点与上游关系，不新增顺序边，也不允许下游事件造成永久死锁。 */
    public String normalize(String targetProcessCode, Set<String> upstreamProcessCodes) {
        for (Rule rule : rules) {
            require(!targetProcessCode.equals(rule.sourceProcessCode()) && upstreamProcessCodes.contains(rule.sourceProcessCode()),
                    "时间规则【" + rule.name() + "】来源必须是目标工序的上游节点，可不相邻");
        }
        try {
            return rules.isEmpty() ? null : JSON.writeValueAsString(this);
        } catch (Exception exception) {
            throw new CheckedException("时间规则序列化失败");
        }
    }

    /** 以同一JSON映射器保存record规则及证据，避免不同JSON库丢失record字段。 */
    public static String encodeEvidence(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw new CheckedException("时间校验证据序列化失败"); }
    }

    /** 编制/下达即拦截不具备同对象语义的组合，不等生产现场才发现数量模式缺标识。 */
    public void validateModes(String target, Map<String, String> processModes) {
        for (Rule rule : rules) {
            if (!"IDENTIFIER".equals(rule.scope())) continue;
            String targetMode = identifierKind(processModes.get(target));
            String sourceMode = identifierKind(processModes.get(rule.sourceProcessCode()));
            require(targetMode != null && targetMode.equals(sourceMode), "时间规则【" + rule.name()
                    + "】来源和目标须同为单件类或同为流转卡；数量模式请选择工单范围");
        }
    }

    private static String identifierKind(String mode) {
        if (mode == null) return null;
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (Set.of("ONE_CODE", "PACKAGE_ONE_CODE", "CODE", "SN_REPORT").contains(normalized)) return "SN";
        return "TRANSFER_CARD".equals(normalized) ? "CARD" : null;
    }

    private static void checkSeconds(String value) {
        if (value == null) return;
        require(value.matches("0|[1-9][0-9]{0,8}") && Long.parseLong(value) <= MAX_SECONDS,
                "时间边界须为0—315360000整数秒，不接受小数或指数");
    }

    /** 获取已通过DAG校验的全部祖先，支持跨过中间工序。 */
    public static Set<String> ancestors(String target, Map<String, ? extends Collection<String>> predecessors) {
        Set<String> result = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        if (predecessors.get(target) != null) queue.addAll(predecessors.get(target));
        while (!queue.isEmpty()) {
            String code = queue.removeFirst();
            if (result.add(code) && predecessors.get(code) != null) queue.addAll(predecessors.get(code));
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
