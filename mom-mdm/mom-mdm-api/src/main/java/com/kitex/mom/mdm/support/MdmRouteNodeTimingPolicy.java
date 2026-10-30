package com.kitex.mom.mdm.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工程节点时间规则的独立协议适配器。
 *
 * <p>沿用V4的上下限、例外、计时和访问语义，但来源始终为稳定节点。
 * 只在主工序与节点一一对应时生成旧生产协议，禁止把RN编号冒充主工序编码。</p>
 */
public final class MdmRouteNodeTimingPolicy {

    public static final String VERSION = "MOM_NODE_TIMING_V1";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private MdmRouteNodeTimingPolicy() {
    }

    /**
     * 严格解析已冻结的节点规则；工程编辑的临时C键不能进入生产执行。
     */
    public static Policy parse(String raw) {
        MdmProcessTimingPolicy validated = MdmProcessTimingPolicy.parse(transform(raw, true, Map.of()));
        List<Rule> rules = validated.rules().stream().map(rule -> {
            String node = rule.sourceProcessCode();
            if (node.length() > 64 || !node.matches("RN-[1-9][0-9]*")) {
                throw new CheckedException("冻结时间来源必须是稳定路线节点，不能使用临时键或主工序编码");
            }
            return new Rule(rule.code(), rule.name(), node, rule.scope(), rule.sourceEvent(),
                    rule.minSeconds(), rule.maxSeconds(), rule.lateExceptionSeconds(), rule.clockMode(), rule.sourceVisit());
        }).toList();
        return new Policy(VERSION, rules);
    }

    /**
     * 校验并规范化节点规则；临时键映射必须来自同一次服务器身份协调。
     */
    public static String normalize(String raw, Map<String, String> keys, String target,
                                   Set<String> ancestors, Map<String, String> modes) {
        String transformed = transform(raw, true, keys);
        MdmProcessTimingPolicy policy = MdmProcessTimingPolicy.parse(transformed);
        String normalized = policy.normalize(target, ancestors);
        policy.validateModes(target, modes);
        return transform(normalized, false, Map.of());
    }

    /**
     * 旧唯一图编辑读取时投影；不修改原规则及已冻结历史。
     */
    public static String fromLegacy(String raw, Map<String, String> processNodes) {
        MdmProcessTimingPolicy.parse(raw);
        return transform(raw, false, processNodes);
    }

    /**
     * 供旧生产消费者使用的无损投影；调用方先保证整图主工序唯一。
     */
    public static String toLegacy(String raw, Map<String, String> nodeProcesses) {
        String result = transform(raw, true, nodeProcesses);
        MdmProcessTimingPolicy.parse(result);
        return result;
    }

    /**
     * 复制工程图时只重写节点来源，不复用源路线身份。
     */
    public static String remap(String raw, Map<String, String> nodeKeys) {
        return transform(transform(raw, true, nodeKeys), false, Map.of());
    }

    private static String transform(String raw, boolean nodeToLegacy, Map<String, String> mapping) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > 32768) throw new CheckedException("节点时间规则过长");
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (!(parsed instanceof ObjectNode root) || !root.path("rules").isArray()) {
                throw new CheckedException("节点时间规则必须包含规则数组");
            }
            if (nodeToLegacy && !VERSION.equals(root.path("version").asText())) {
                throw new CheckedException("不支持的节点时间规则版本，请升级工艺配置页面");
            }
            String from = nodeToLegacy ? "sourceRouteNodeCode" : "sourceProcessCode";
            String to = nodeToLegacy ? "sourceProcessCode" : "sourceRouteNodeCode";
            for (JsonNode value : root.path("rules")) {
                if (!(value instanceof ObjectNode rule) || !rule.path(from).isTextual() || rule.has(to)) {
                    throw new CheckedException("节点时间来源缺失或混用了主工序协议");
                }
                String source = rule.path(from).asText();
                String replaced = mapping.isEmpty() ? source : mapping.get(source);
                if (replaced == null) throw new CheckedException("时间来源不在当前路线中：" + source);
                rule.remove(from);
                rule.put(to, replaced);
            }
            root.put("version", nodeToLegacy ? MdmProcessTimingPolicy.VERSION : VERSION);
            String result = JSON.writeValueAsString(root);
            // 转为原严格记录解析后，未知字段、数值格式及版本限制仍不能被绕过。
            if (nodeToLegacy) MdmProcessTimingPolicy.parse(result);
            return result;
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("节点时间规则JSON格式无效");
        }
    }

    /**
     * 原生节点来源，不将稳定节点码暴露为主工序编码。
     */
    public record Policy(String version, List<Rule> rules) {
    }

    /**
     * @param code                 目标节点内唯一规则编码
     * @param name                 现场提示使用的规则名称
     * @param sourceRouteNodeCode  原冻结路线内精确来源节点，允许非相邻祖先
     * @param scope                WORK_ORDER工单范围或IDENTIFIER同一规范标识
     * @param sourceEvent          实际START/FINISH或有效合格报工的ACCEPTED_REPORT
     * @param minSeconds           最短等待整数秒，空表示无下限
     * @param maxSeconds           最长间隔整数秒，空表示无上限
     * @param lateExceptionSeconds 独立审批可延长的最大秒数，不影响最短等待
     * @param clockMode            自然时间或只扣除目标派工的真实暂停
     * @param sourceVisit          首轮或批准路线中的有效来源轮次
     */
    public record Rule(String code, String name, String sourceRouteNodeCode, String scope,
                       String sourceEvent, String minSeconds, String maxSeconds,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String lateExceptionSeconds,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String clockMode,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String sourceVisit) {

        /**
         * 只复用时间计算参数；调用方须先用冻结图解析出来源的真实主工序。
         */
        public MdmProcessTimingPolicy.Rule calculation(String actualProcessCode) {
            return new MdmProcessTimingPolicy.Rule(code, name, actualProcessCode, scope, sourceEvent,
                    minSeconds, maxSeconds, lateExceptionSeconds, clockMode, sourceVisit);
        }
    }
}
