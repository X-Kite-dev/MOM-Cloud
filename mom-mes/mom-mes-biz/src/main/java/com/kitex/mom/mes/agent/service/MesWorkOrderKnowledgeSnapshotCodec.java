package com.kitex.mom.mes.agent.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mes.agent.dto.MesWorkOrderKnowledgeDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 有界解析MES保存的原始快照；绝不查询当前MDM补写历史。
 * SHA只核原UTF8字节，解析数字保留BigInteger/BigDecimal，SOP地址仅参与引用摘要而不公开。
 */
@Component
public class MesWorkOrderKnowledgeSnapshotCodec {
    /**
     * 单份原JSON及最终派生投影各自的UTF8字节上限。
     */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> DATA_TYPES = Set.of("DECIMAL", "INTEGER");
    private static final Set<String> AGGREGATIONS = Set.of("RANGE", "AVERAGE");
    private final ObjectMapper json;

    public MesWorkOrderKnowledgeSnapshotCodec(ObjectMapper mapper) {
        // 不修改共享Mapper；旧Hutool快照的数值Long和decimal不能经double再投影。
        json = mapper.copy().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,
                        DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        json.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }

    /**
     * 原UTF8字节摘要，不重新序列化数字、空字段或日期。
     */
    public static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("无法计算原快照SHA256", impossible);
        }
    }

    /**
     * 精确快照引用；调用方不能把本次观察摘要标成持久防篡改证明。
     */
    public static String proofRef(String base, String kind, String hash) {
        return base + "/snapshot/" + kind + "/" + hash;
    }

    private static MesWorkOrderKnowledgeDTO.SnapshotProof missing(String kind) {
        return new MesWorkOrderKnowledgeDTO.SnapshotProof(kind, "MISSING", null, "NONE", null);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static void require(boolean valid, String gap) {
        if (!valid) {
            throw new SnapshotFailure("INVALID_SNAPSHOT", gap);
        }
    }

    private static void bound(boolean valid, String gap) {
        if (!valid) {
            throw new SnapshotFailure("OVERSIZED", gap);
        }
    }

    /**
     * 解析原工单三类冻结快照，校验参数原保存SHA及路线归属，再派生有界知识。
     *
     * @param task 从同一只读快照取得的MES工单
     * @param base 由已核租户、工厂及原工单主键组成的来源根引用
     * @return 已验证的原快照证明、工序、参数与缺口；不含任意原JSON或URL
     * @throws SnapshotFailure 未冻结、原数据损坏或超容量，调用方应关闭正文
     */
    public Bundle decode(MesTaskEntity task, String base) {
        if ("CREATE".equals(task.getStatus()) || !StringUtils.hasText(task.getProcessRouteSnapshot())) {
            throw new SnapshotFailure("NOT_FROZEN", "工单尚无可读取的下达冻结工艺；不得套用当前MDM标准");
        }
        require(task.getProcessRouteId() != null && task.getProcessRouteId() > 0
                && StringUtils.hasText(task.getProcessRouteCode()), "冻结路线缺少原ID或编码");
        JsonNode route = parse(task.getProcessRouteSnapshot());
        require(route.isArray() && !route.isEmpty(), "冻结路线必须为非空工序数组");
        bound(route.size() <= 500, "冻结工序超过500项");
        String routeHash = sha256(task.getProcessRouteSnapshot());
        String routeRef = proofRef(base, "PROCESS_ROUTE", routeHash);
        boolean hasSnapshot = StringUtils.hasText(task.getProcessParameterStandardSnapshot());
        boolean hasHash = StringUtils.hasText(task.getProcessParameterSnapshotFingerprint());
        require(hasSnapshot == hasHash, "工单参数快照和保存指纹缺失不一致");
        JsonNode parameterRaw = null;
        boolean nodeProtocol = false;
        if (hasSnapshot) {
            // 原字节只验证一次；协议分支不会重新序列化或写回历史快照。
            boundedBytes(task.getProcessParameterStandardSnapshot());
            String hash = task.getProcessParameterSnapshotFingerprint();
            require(hash.matches("[0-9a-f]{64}") && hash.equals(sha256(task.getProcessParameterStandardSnapshot())),
                    "工单原参数快照保存指纹不一致");
            parameterRaw = parse(task.getProcessParameterStandardSnapshot());
            nodeProtocol = nodeProtocol(parameterRaw);
        }
        List<String> gaps = new ArrayList<>();
        List<MesWorkOrderKnowledgeDTO.Process> processes = processes(route, task.getFactoryCode(), routeRef, gaps, nodeProtocol);
        List<MesWorkOrderKnowledgeDTO.SnapshotProof> proofs = new ArrayList<>();
        proofs.add(new MesWorkOrderKnowledgeDTO.SnapshotProof("PROCESS_ROUTE", "PRESENT", routeHash,
                "OBSERVED_SHA256", routeRef));
        if (StringUtils.hasText(task.getBomSnapshot())) {
            JsonNode bom = parse(task.getBomSnapshot());
            require((bom.isArray() || bom.isObject()) && task.getBomId() != null && task.getBomId() > 0,
                    "冻结BOM缺少原身份或JSON结构非法");
            String hash = sha256(task.getBomSnapshot());
            proofs.add(new MesWorkOrderKnowledgeDTO.SnapshotProof("BOM", "PRESENT", hash,
                    "OBSERVED_SHA256", proofRef(base, "BOM", hash)));
        } else {
            proofs.add(missing("BOM"));
            gaps.add("工单缺少原BOM快照，本次不证明其BOM依据；不查询当前BOM补齐");
        }
        MesWorkOrderKnowledgeDTO.Standard standard = null;
        if (hasSnapshot) {
            String hash = task.getProcessParameterSnapshotFingerprint();
            standard = standard(parameterRaw, task, processes, nodeProtocol);
            proofs.add(new MesWorkOrderKnowledgeDTO.SnapshotProof("PROCESS_PARAMETER_STANDARD", "PRESENT", hash,
                    "PERSISTED_SHA256", proofRef(base, "PROCESS_PARAMETER_STANDARD", hash)));
            gaps.addAll(standard.dataGaps());
            if (!standard.available()) {
                gaps.add("该工单当时明确未冻结可用正式参数标准；不得套用现行标准");
            }
        } else {
            proofs.add(missing("PROCESS_PARAMETER_STANDARD"));
            gaps.add("工单未保存参数标准冻结证据，历史参数依据未证明；不得回填现行标准");
        }
        return new Bundle(List.copyOf(proofs), List.copyOf(processes), standard, gaps.stream().distinct().toList());
    }

    private List<MesWorkOrderKnowledgeDTO.Process> processes(JsonNode route, String factory, String routeRef,
                                                             List<String> gaps, boolean nodeProtocol) {
        List<MesWorkOrderKnowledgeDTO.Process> result = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        int sopCount = 0;
        for (JsonNode row : route) {
            require(row.isObject(), "冻结工序不是对象");
            String code = text(row, "processCode", 100, false);
            if (!StringUtils.hasText(code)) {
                code = text(row, "routeCode", 100, true);
            }
            String node = nodeProtocol ? text(row, "routeNodeCode", 64, true) : null;
            require(!nodeProtocol || node.matches("RN-[1-9][0-9]{0,18}"), "冻结路线节点身份非法");
            require(codes.add(nodeProtocol ? node : code), "冻结工序或节点编码重复，不能猜测归属");
            String frozenFactory = text(row, "factoryCode", 100, false);
            require(frozenFactory == null || factory.equals(frozenFactory), "冻结工序工厂与原工单不一致");
            String name = text(row, "processName", 500, false);
            if (name == null) {
                name = text(row, "routeName", 500, false);
            }
            Integer order = integer(row, "routeOrder", false);
            if (order == null) {
                order = integer(row, "orderNum", false);
            }
            require(order == null || order >= 0, "冻结工序顺序非法");
            JsonNode sops = row.path("mdmProcessProcedureSopList");
            String state = "LEGACY_UNDECLARED";
            List<MesWorkOrderKnowledgeDTO.SopReference> refs = new ArrayList<>();
            if (!sops.isMissingNode() && !sops.isNull()) {
                require(sops.isArray(), "冻结SOP引用不是数组");
                sopCount += sops.size();
                bound(sopCount <= 500, "冻结SOP引用总数超过500项");
                state = sops.isEmpty() ? "NO_REFERENCE" : "REFERENCE_ONLY";
                Set<String> hashes = new HashSet<>();
                for (JsonNode sop : sops) {
                    require(sop.isObject(), "冻结SOP引用不是对象");
                    String sopName = text(sop, "name", 500, true);
                    String url = text(sop, "url", 3000, false);
                    String fileName = text(sop, "fileName", 3000, false);
                    require(StringUtils.hasText(url) || StringUtils.hasText(fileName), "冻结SOP缺少原文件引用");
                    String id = id(sop, "id", false);
                    String hash = sha256(sop.toString());
                    require(hashes.add(hash), "同工序冻结SOP引用重复");
                    // 地址不输出；hash只标识JSON引用，不标识或批准文件字节。
                    refs.add(new MesWorkOrderKnowledgeDTO.SopReference(id, sopName, hash, routeRef + "/sop/" + hash));
                }
            }
            if ("LEGACY_UNDECLARED".equals(state)) {
                gaps.add("工序" + code + "的原SOP清单未声明，不能按空清单处理");
            }
            if ("REFERENCE_ONLY".equals(state)) {
                gaps.add("工序" + code + "只证明原SOP引用，不证明批准文件版次或原始字节");
            }
            result.add(new MesWorkOrderKnowledgeDTO.Process(code, name, order, state, List.copyOf(refs), routeRef, node));
        }
        return result;
    }

    private MesWorkOrderKnowledgeDTO.Standard standard(JsonNode raw, MesTaskEntity task,
                                                       List<MesWorkOrderKnowledgeDTO.Process> processes, boolean nodeProtocol) {
        require(raw.isObject() && raw.path("available").isBoolean(), "原参数快照缺少明确可用状态");
        boolean available = raw.path("available").booleanValue();
        String contentSchema = text(raw, "contentSchemaVersion", 40, nodeProtocol && available);
        require(contentSchema == null || MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1.equals(contentSchema)
                        || (nodeProtocol && MdmProcessParameterStandardDTO.NODE_V2.equals(contentSchema)),
                "原参数内容协议与冻结协议不一致");
        String routeId = id(raw, "processRouteId", true);
        String routeCode = text(raw, "routeCode", 100, true);
        require(String.valueOf(task.getProcessRouteId()).equals(routeId) && task.getProcessRouteCode().equals(routeCode),
                "原参数快照路线身份与工单不一致");
        String standardId = id(raw, "standardId", available);
        String no = text(raw, "standardNo", 100, available);
        String name = text(raw, "standardName", 500, available);
        Integer version = integer(raw, "versionNo", available);
        String fingerprint = text(raw, "contentFingerprint", 64, available);
        require(Objects.equals(no, blankToNull(task.getProcessParameterStandardNo()))
                && Objects.equals(version, task.getProcessParameterStandardVersion()), "原参数快照标准编号或版本与工单头不一致");
        if (available) {
            require(version != null && version > 0 && fingerprint.matches("[0-9a-f]{64}"), "原标准版本或指纹非法");
        }
        LocalDateTime effective = time(raw, "effectiveTime", available);
        LocalDateTime released = time(raw, "releasedTime", available);
        LocalDateTime resolved = time(raw, "resolvedAt", true);
        require((effective == null || !effective.isAfter(resolved)) && (released == null || !released.isAfter(resolved)),
                "原标准生效或发布时间晚于冻结取证时间");
        JsonNode rows = raw.path("parameters");
        require(rows.isArray(), "原参数快照缺少参数数组");
        bound(rows.size() <= 500, "冻结参数超过500项");
        if (!available) {
            require(standardId == null && no == null && name == null && version == null
                            && fingerprint == null && effective == null && released == null && rows.isEmpty(),
                    "不可用原标准仍含正式身份或正文");
        }
        Set<String> processCodes = new HashSet<>();
        processes.forEach(process -> processCodes.add(process.processCode()));
        Map<String, String> nodeProcesses = new LinkedHashMap<>();
        if (nodeProtocol) {
            processes.forEach(process -> nodeProcesses.put(process.routeNodeCode(), process.processCode()));
        }
        JsonNode frozenNodes = raw.path("routeNodeProcesses");
        if (nodeProtocol) {
            require(frozenNodes.isObject() && frozenNodes.size() == nodeProcesses.size(),
                    "原参数冻结缺少完整真实节点映射");
            nodeProcesses.forEach((node, process) -> require(frozenNodes.path(node).isTextual()
                    && process.equals(frozenNodes.path(node).textValue()), "原参数节点映射与冻结路线不一致"));
        } else {
            require(frozenNodes.isMissingNode() || frozenNodes.isNull()
                    || (frozenNodes.isObject() && frozenNodes.isEmpty()), "历史V1冻结不能携带未声明的节点映射");
        }
        Set<String> ids = new HashSet<>();
        Set<String> identifiers = new HashSet<>();
        List<MesWorkOrderKnowledgeDTO.Parameter> parameters = new ArrayList<>();
        for (JsonNode parameter : rows) {
            require(parameter.isObject(), "原冻结参数不是对象");
            String itemId = id(parameter, "sourceItemId", true);
            String code = text(parameter, "processCode", 100, true);
            String node = text(parameter, "routeNodeCode", 64, nodeProtocol);
            String identifier = text(parameter, "identifier", 100, true);
            require(ids.add(itemId) && processCodes.contains(code)
                            && (nodeProtocol ? Objects.equals(nodeProcesses.get(node), code) : node == null)
                            && identifiers.add((nodeProtocol ? node : code) + "\u0000" + identifier),
                    "冻结参数身份重复、协议混用或节点/主工序不属于工单原路线");
            String type = text(parameter, "dataType", 20, true);
            String aggregation = text(parameter, "aggregationMode", 20, true);
            require(DATA_TYPES.contains(type) && AGGREGATIONS.contains(aggregation), "冻结参数类型或聚合方式非法");
            require(parameter.path("required").isBoolean(), "冻结参数缺少明确必需规则");
            Integer samples = integer(parameter, "minimumSampleCount", true);
            Integer seconds = integer(parameter, "continuousDeviationSeconds", true);
            require(samples >= 1 && samples <= 1_000_000 && seconds >= 0 && seconds <= 86400, "冻结参数采样或持续秒数非法");
            BigDecimal target = decimal(parameter, "targetValue");
            BigDecimal lower = decimal(parameter, "lowerLimit");
            BigDecimal upper = decimal(parameter, "upperLimit");
            BigDecimal warningLower = decimal(parameter, "warningLowerLimit");
            BigDecimal warningUpper = decimal(parameter, "warningUpperLimit");
            BigDecimal nonGood = decimal(parameter, "maximumNonGoodRatePct");
            limits(target, lower, upper, warningLower, warningUpper, nonGood);
            parameters.add(new MesWorkOrderKnowledgeDTO.Parameter(itemId, code, text(parameter, "processName", 500, false),
                    identifier, text(parameter, "parameterName", 500, true), type, text(parameter, "unit", 100, false),
                    plain(target), plain(lower), plain(upper), plain(warningLower), plain(warningUpper),
                    parameter.path("required").booleanValue(), aggregation, samples, plain(nonGood), seconds, node));
        }
        List<String> gaps = gaps(raw.path("dataGaps"));
        if (available) {
            for (MesWorkOrderKnowledgeDTO.Process process : processes) {
                if (parameters.stream().noneMatch(parameter -> nodeProtocol
                        ? process.routeNodeCode().equals(parameter.routeNodeCode())
                        : process.processCode().equals(parameter.processCode()))) {
                    gaps.add((nodeProtocol ? "节点" + process.routeNodeCode() : "工序" + process.processCode())
                            + "未冻结受控参数，不能猜测控制限");
                }
            }
        }
        return new MesWorkOrderKnowledgeDTO.Standard(available, standardId, no, name, version, routeId, routeCode,
                text(raw, "routeName", 500, false), effective, released, fingerprint, resolved,
                List.copyOf(parameters), gaps.stream().distinct().toList(),
                nodeProtocol ? MdmProcessParameterStandardDTO.ROUTE_NODE_V2 : null,
                nodeProtocol ? contentSchema : null);
    }

    /**
     * 缺省仅指历史V1，未知协议不能通过字段缺省降级。
     */
    private boolean nodeProtocol(JsonNode raw) {
        require(raw.isObject(), "原参数冻结不是对象");
        String schema = text(raw, "snapshotSchemaVersion", 40, false);
        if (MdmProcessParameterStandardDTO.ROUTE_NODE_V2.equals(schema)) {
            return true;
        }
        if (schema == null || MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1.equals(schema)) {
            return false;
        }
        throw new SnapshotFailure("UNSUPPORTED_SNAPSHOT_SCHEMA", "工单参数冻结协议不兼容，请升级节点知识消费者");
    }

    private void limits(BigDecimal target, BigDecimal lower, BigDecimal upper, BigDecimal warningLower,
                        BigDecimal warningUpper, BigDecimal nonGood) {
        require(lower != null || upper != null, "冻结参数未定义控制限");
        require(lower == null || upper == null || lower.compareTo(upper) <= 0, "冻结控制限顺序非法");
        require(target == null || lower == null || target.compareTo(lower) >= 0, "冻结目标值低于控制限");
        require(target == null || upper == null || target.compareTo(upper) <= 0, "冻结目标值高于控制限");
        require(warningLower == null || lower == null || warningLower.compareTo(lower) >= 0, "冻结预警下限非法");
        require(warningUpper == null || upper == null || warningUpper.compareTo(upper) <= 0, "冻结预警上限非法");
        require(warningLower == null || warningUpper == null || warningLower.compareTo(warningUpper) <= 0, "冻结预警限顺序非法");
        require(nonGood != null && nonGood.compareTo(BigDecimal.ZERO) >= 0 && nonGood.compareTo(BigDecimal.valueOf(100)) <= 0,
                "冻结非GOOD比例非法");
    }

    private JsonNode parse(String raw) {
        boundedBytes(raw);
        try {
            return json.readTree(raw);
        } catch (JsonProcessingException invalid) {
            throw new SnapshotFailure("INVALID_SNAPSHOT", "工单原冻结JSON损坏或存在重复字段，禁止读取正文");
        }
    }

    private void boundedBytes(String raw) {
        bound(raw.length() <= MAX_BYTES && raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES, "原冻结快照超过2MiB");
    }

    private String text(JsonNode row, String field, int maximum, boolean required) {
        JsonNode value = row.path(field);
        if (value.isNull() || value.isMissingNode()) {
            require(!required, "冻结字段缺失：" + field);
            return null;
        }
        require(value.isTextual() && value.textValue().length() <= maximum, "冻结文本字段非法：" + field);
        String text = value.textValue();
        require(!required || StringUtils.hasText(text), "冻结文本字段为空：" + field);
        require(text.chars().noneMatch(c -> c < 32 || c == 127), "冻结文本包含控制字符：" + field);
        return text;
    }

    private String id(JsonNode row, String field, boolean required) {
        JsonNode value = row.path(field);
        if (value.isNull() || value.isMissingNode()) {
            require(!required, "冻结ID缺失：" + field);
            return null;
        }
        require(value.isTextual() || value.isIntegralNumber(), "冻结ID不是精确整数：" + field);
        String text = value.asText();
        require(text.matches("[1-9][0-9]{0,18}") && new BigInteger(text).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0,
                "冻结ID非法或溢出：" + field);
        return text;
    }

    private Integer integer(JsonNode row, String field, boolean required) {
        JsonNode value = row.path(field);
        if (value.isNull() || value.isMissingNode()) {
            require(!required, "冻结整数缺失：" + field);
            return null;
        }
        require(value.isIntegralNumber() && value.canConvertToInt(), "冻结整数类型或范围非法：" + field);
        return value.intValue();
    }

    private BigDecimal decimal(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        require(value.isNumber() || value.isTextual(), "冻结decimal类型非法：" + field);
        String text = value.asText();
        require(text.length() <= 128 && text.matches("-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,3})?"), "冻结decimal文本非法：" + field);
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException invalid) {
            throw new SnapshotFailure("INVALID_SNAPSHOT", "冻结decimal不能精确解析");
        }
        require(Math.abs((long) decimal.scale()) <= 128 && decimal.precision() <= 128
                && decimal.toPlainString().length() <= 128, "冻结decimal超过精确展示边界");
        return decimal;
    }

    /**
     * 旧Hutool默认epoch毫秒按明确业务时区解码，绝不依赖当前主机默认时区。
     */
    private LocalDateTime time(JsonNode row, String field, boolean required) {
        JsonNode value = row.path(field);
        if (value.isNull() || value.isMissingNode()) {
            require(!required, "冻结时间缺失：" + field);
            return null;
        }
        try {
            LocalDateTime result;
            if (value.isIntegralNumber() && value.canConvertToLong()) {
                result = LocalDateTime.ofInstant(Instant.ofEpochMilli(value.longValue()), BUSINESS_ZONE);
            } else {
                require(value.isTextual() && value.textValue().length() <= 40, "冻结时间类型非法：" + field);
                result = LocalDateTime.parse(value.textValue().replace(' ', 'T'));
            }
            require(result.getYear() >= 1900 && result.getYear() <= 9999, "冻结时间超出业务边界：" + field);
            return result;
        } catch (java.time.DateTimeException invalid) {
            throw new SnapshotFailure("INVALID_SNAPSHOT", "冻结时间不能按原合同解析");
        }
    }

    private List<String> gaps(JsonNode rows) {
        require(rows.isArray() && rows.size() <= 500, "原标准缺口清单非法或超限");
        List<String> result = new ArrayList<>();
        for (JsonNode value : rows) {
            require(value.isTextual() && StringUtils.hasText(value.textValue()) && value.textValue().length() <= 1000,
                    "原标准缺口不是有界文本");
            result.add(value.textValue());
        }
        return result;
    }

    /**
     * 原冻结快照验证后的内部投影，不作为新增数据库实体。
     *
     * @param snapshots 三类原快照证明
     * @param processes 有界原工序和SOP引用
     * @param standard  原标准投影，未保存时为空
     * @param dataGaps  已去重的适用缺口
     */
    public record Bundle(List<MesWorkOrderKnowledgeDTO.SnapshotProof> snapshots,
                         List<MesWorkOrderKnowledgeDTO.Process> processes,
                         MesWorkOrderKnowledgeDTO.Standard standard, List<String> dataGaps) {
    }

    /**
     * 只捕获可预期快照问题；权限、数据库及依赖故障必须保持失败。
     */
    public static final class SnapshotFailure extends RuntimeException {
        private final String state;

        public SnapshotFailure(String state, String gap) {
            super(gap);
            this.state = state;
        }

        /**
         * 供只读响应区分未冻结、损坏和超限；不暴露任意底层异常。
         */
        public String state() {
            return state;
        }
    }
}
