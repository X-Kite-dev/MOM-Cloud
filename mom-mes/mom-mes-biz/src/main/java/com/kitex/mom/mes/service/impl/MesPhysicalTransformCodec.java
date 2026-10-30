package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesPhysicalTransformCommandDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * 严格转换正文、文本计量和事实指纹；局部JSON配置不改变旧业务的序列化算法。
 */
@Component
public class MesPhysicalTransformCodec {

    public static final String POLICY_VERSION = "MES_PHYSICAL_TRANSFORM_POLICY_V1";
    public static final String SOURCE_VERSION = "MES_PHYSICAL_SOURCE_V1";
    public static final String PREVIEW_VERSION = "MES_PHYSICAL_PREVIEW_V1";
    public static final String COMMAND_VERSION = "MES_PHYSICAL_TRANSFORM_COMMAND_V1";
    public static final String COMMAND_VERSION_V2 = "MES_PHYSICAL_TRANSFORM_COMMAND_V2";
    /**
     * 永久原号锁保持旧字面域；升级命令算法不能释放同一tenant/requestId。
     */
    public static final String REQUEST_LOCK_VERSION = COMMAND_VERSION;
    public static final int MAX_SOURCES = 50;
    public static final int MAX_OUTPUTS = 1000;
    public static final int MAX_ALLOCATIONS = 50000;

    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    /**
     * 先检查每个JSON字段及类型，禁止Jackson把number转换成字符串或忽略额外字段。
     */
    public MesPhysicalTransformDTO.Plan decode(String rawJson) {
        JsonNode root = tree(rawJson, 16 * 1024 * 1024);
        fields(root, Set.of("expectedTenantId", "expectedFactoryCode", "expectedActorUserId",
                "policyDraft", "inputs", "outputs", "allocations", "reason"));
        texts(root, "expectedTenantId", "expectedFactoryCode", "expectedActorUserId", "policyDraft", "reason");
        planItems(root);
        var result = convert(root, MesPhysicalTransformDTO.Plan.class);
        reason(result.getReason());
        return result;
    }

    /**
     * 严格永久命令与预检分开：没有policyDraft、客户端工艺资格或服务器派工身份。
     */
    public MesPhysicalTransformCommandDTO.Body command(String rawJson) {
        JsonNode root = tree(rawJson, 16 * 1024 * 1024);
        require(root.isObject() && root.path("version").isTextual(), "永久转换版本必须是明确文本字段");
        String version = root.path("version").textValue();
        require(Set.of(COMMAND_VERSION, COMMAND_VERSION_V2).contains(version), "永久转换原命令版本未知");
        boolean v2 = COMMAND_VERSION_V2.equals(version);
        fields(root, v2 ? Set.of("version", "expectedSourceVersion", "expectedTenantId", "expectedFactoryCode", "expectedActorUserId",
                "inputs", "outputs", "allocations", "reason") : Set.of("version", "expectedTenantId", "expectedFactoryCode",
                "expectedActorUserId", "inputs", "outputs", "allocations", "reason"));
        texts(root, "version", "expectedTenantId", "expectedFactoryCode", "expectedActorUserId", "reason");
        if (v2) {
            texts(root, "expectedSourceVersion");
            require(MesPhysicalTransformSourceFreezeService.SOURCE_VERSION.equals(root.get("expectedSourceVersion").textValue()),
                    "V2原命令必须明确使用MES_PHYSICAL_SOURCE_V2，不能按旧观察指纹猜版本");
        }
        planItems(root);
        MesPhysicalTransformCommandDTO.Body result = v2 ? convert(root, MesPhysicalTransformCommandDTO.BodyV2.class)
                : convert(root, MesPhysicalTransformCommandDTO.Body.class);
        id(result.getExpectedTenantId());
        id(result.getExpectedActorUserId());
        code(result.getExpectedFactoryCode(), "观察工厂", 100);
        reason(result.getReason());
        commandItems(result);
        return result;
    }

    /**
     * 原号封存请求也是严格文本协议，避免DTO读取时number到字符串的隐式转换。
     */
    public MesPhysicalTransformCommandDTO.Seal seal(String rawJson) {
        JsonNode root = tree(rawJson, 16 * 1024);
        fields(root, Set.of("expectedFingerprint", "reason"));
        texts(root, "expectedFingerprint", "reason");
        var result = convert(root, MesPhysicalTransformCommandDTO.Seal.class);
        require(result.getExpectedFingerprint().matches("[0-9a-f]{64}"), "封存原正文指纹必须为SHA-256文本");
        reason(result.getReason());
        return result;
    }

    /**
     * 两种协议共用字段形状检查，但不混用政策草案与正式命令权限。
     */
    private void planItems(JsonNode root) {
        array(root, "inputs", 1, MAX_SOURCES);
        array(root, "outputs", 1, MAX_OUTPUTS);
        array(root, "allocations", 1, MAX_ALLOCATIONS);
        for (JsonNode input : root.get("inputs")) {
            fields(input, Set.of("stockId", "expectedFingerprint", "quantity"));
            texts(input, "stockId", "expectedFingerprint", "quantity");
        }
        for (JsonNode output : root.get("outputs")) {
            fields(output, Set.of("outputKey", "identifier", "objectType", "quantity", "disposition", "positionCode"));
            texts(output, "outputKey", "identifier", "objectType", "quantity", "disposition", "positionCode");
        }
        for (JsonNode edge : root.get("allocations")) {
            fields(edge, Set.of("stockId", "outputKey", "quantity"));
            texts(edge, "stockId", "outputKey", "quantity");
        }
    }

    /**
     * 登记阶段仅核传输完整性及输出分配；投入当量/工艺/原事实须后续锁内再核。
     */
    private void commandItems(MesPhysicalTransformCommandDTO.Body body) {
        Set<String> inputIds = new HashSet<>();
        for (var input : body.getInputs()) {
            id(input.getStockId());
            require(inputIds.add(input.getStockId()), "精确投入库存不能重复");
            require(input.getExpectedFingerprint().matches("[0-9a-f]{64}"), "每源完整观察指纹必须为SHA-256文本");
            quantity(input.getQuantity(), false, "投入量");
        }
        Map<String, BigDecimal> outputQuantities = new HashMap<>();
        Set<String> identifiers = new HashSet<>();
        Set<String> positions = new HashSet<>();
        Set<String> missing = new HashSet<>();
        for (var output : body.getOutputs()) {
            code(output.getOutputKey(), "输出键", 100);
            require(!outputQuantities.containsKey(output.getOutputKey()), "输出键不能重复");
            require(Set.of("BATCH", "SN", "TRAVELER").contains(output.getObjectType()), "产出对象未知");
            require(Set.of("GOOD", "SCRAP", "MISSING").contains(output.getDisposition()), "产出处置未知");
            boolean absent = "MISSING".equals(output.getDisposition());
            BigDecimal amount = quantity(output.getQuantity(), absent, "产出量");
            if (absent) {
                require(amount.signum() == 0 && output.getIdentifier().isEmpty() && "SN".equals(output.getObjectType()),
                        "缺位须零量/空标识/SN位置，不产生实物");
                missing.add(output.getOutputKey());
            } else {
                code(output.getIdentifier(), "产出标识", 100);
                require(identifiers.add(output.getIdentifier()), "产出标识不能重复");
                require(!"SN".equals(output.getObjectType()) || amount.compareTo(BigDecimal.ONE) == 0, "单件SN必须完整1件");
            }
            if (!output.getPositionCode().isEmpty()) {
                code(output.getPositionCode(), "位置编码", 100);
                require(positions.add(output.getPositionCode()), "位置编码不能重复");
            }
            require(!absent || !output.getPositionCode().isEmpty(), "缺位须指定原位置，不是假空产出");
            outputQuantities.put(output.getOutputKey(), amount);
        }
        Set<String> edges = new HashSet<>();
        Set<String> allocatedInputs = new HashSet<>();
        Map<String, BigDecimal> allocatedOutputs = new HashMap<>();
        for (var edge : body.getAllocations()) {
            require(inputIds.contains(edge.getStockId()) && outputQuantities.containsKey(edge.getOutputKey())
                    && !missing.contains(edge.getOutputKey()), "分配引用范围外来源/产出或缺位");
            require(edges.add(snapshot(List.of(edge.getStockId(), edge.getOutputKey()))), "每源到每产出的分配边不能重复");
            BigDecimal amount = quantity(edge.getQuantity(), false, "分配当量");
            allocatedInputs.add(edge.getStockId());
            allocatedOutputs.merge(edge.getOutputKey(), amount, BigDecimal::add);
        }
        require(allocatedInputs.equals(inputIds), "每一来源必须有分配，不能漏源");
        outputQuantities.forEach((key, amount) -> require(amount.compareTo(allocatedOutputs.getOrDefault(key, BigDecimal.ZERO)) == 0,
                "产出" + key + "的全部来源分配不完整"));
    }

    /**
     * 只返回数学政策草案；不证明工程发布、单位资格或现场工艺版本。
     */
    public MesPhysicalTransformDTO.Policy policy(String rawJson) {
        JsonNode root = tree(rawJson, 256 * 1024);
        fields(root, Set.of("version", "operationType", "inputObjectType", "outputObjectType", "inputUnit",
                "outputUnit", "equivalenceFactor", "partialInputAllowed", "positionCodes"));
        texts(root, "version", "operationType", "inputObjectType", "outputObjectType", "inputUnit", "outputUnit",
                "equivalenceFactor");
        require(root.get("partialInputAllowed").isBoolean(), "partialInputAllowed必须是boolean");
        array(root, "positionCodes", 0, MAX_OUTPUTS);
        for (JsonNode position : root.get("positionCodes")) {
            require(position.isTextual(), "位置编码必须为文本");
            code(position.textValue(), "位置编码", 100);
        }
        var result = convert(root, MesPhysicalTransformDTO.Policy.class);
        require(POLICY_VERSION.equals(result.getVersion()), "不支持的实物转换政策版本");
        require(Set.of("SPLIT", "MERGE", "REBATCH", "DEPANEL").contains(result.getOperationType()), "不支持的转换动作");
        require(Set.of("BATCH", "SN", "TRAVELER", "PANEL").contains(result.getInputObjectType()), "不支持的投入对象");
        require(Set.of("BATCH", "SN", "TRAVELER").contains(result.getOutputObjectType()), "不支持的产出对象");
        code(result.getInputUnit(), "投入单位", 100);
        code(result.getOutputUnit(), "当量单位", 100);
        BigDecimal factor = quantity(result.getEquivalenceFactor(), false, "当量系数");
        require(!result.getInputUnit().equals(result.getOutputUnit()) || factor.compareTo(BigDecimal.ONE) == 0,
                "同一单位的当量系数只能为1，不能用系数增加同单位产量");
        require(result.getPositionCodes().stream().distinct().count() == result.getPositionCodes().size(), "政策位置不能重复");
        boolean depanel = "DEPANEL".equals(result.getOperationType());
        require(depanel == !result.getPositionCodes().isEmpty(), "仅拆板动作必须声明完整非空位置集合");
        require(!depanel || "PANEL".equals(result.getInputObjectType()) && "SN".equals(result.getOutputObjectType()),
                "拆板必须是PANEL投入至逐SN输出");
        require(depanel || !"PANEL".equals(result.getInputObjectType()), "PANEL对象须使用明确位置拆板动作");
        require(!"SN".equals(result.getInputObjectType()) || !result.getPartialInputAllowed(), "SN不能允许部分投入");
        require(!depanel || !result.getPartialInputAllowed() || factor.compareTo(BigDecimal.ONE) == 0,
                "部分拆板必须沿同单位位置数量，不能用分数面板代替真实剩余位置");
        return result;
    }

    /**
     * 精确正Long文本，拒绝前导零、超界与浮点转换。
     */
    public Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "库存及实名ID须为精确正整数文本");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new CheckedException("ID超出Long范围，不得截断");
        }
    }

    /**
     * 28位有效数字、8位小数，不支持指数/符号/空白和隐式舍入。
     */
    public BigDecimal quantity(String value, boolean allowZero, String label) {
        require(value != null && value.length() <= 40 && value.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?"),
                label + "须为无指数和符号的精确小数文本");
        BigDecimal result = new BigDecimal(value);
        require(result.precision() <= 28 && result.scale() <= 8 && (allowZero ? result.signum() >= 0 : result.signum() > 0),
                label + "须满足28位有效数字/8位小数及有效正量规则");
        return result;
    }

    /**
     * 计算结果必须仍精确可表示，不能将超精度当量四舍五入。
     */
    public String amount(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        require(value.signum() >= 0 && normalized.scale() <= 8
                && normalized.precision() + Math.max(0, -normalized.scale()) <= 28, "计算当量超出28位/8位精度，禁止舍入");
        return normalized.toPlainString();
    }

    /**
     * 标识/单位/键保持原文；首尾空白与Web/PG使用同一显式字符集，不依赖strip/trim差异。
     */
    public String code(String value, String label, int maxLength) {
        require(value != null && !value.isEmpty() && value.length() <= maxLength
                && !boundaryWhitespace(value.charAt(0)) && !boundaryWhitespace(value.charAt(value.length() - 1))
                && value.codePoints().noneMatch(Character::isISOControl), label + "为空、过长、含控制字符或首尾空白");
        return value;
    }

    /**
     * C0/C1另行全串拒绝；此集覆盖合法Unicode空白及BOM，三层协议保持一致。
     */
    private boolean boundaryWhitespace(char value) {
        return value == '\u0020' || value == '\u00a0' || value == '\u1680'
                || (value >= '\u2000' && value <= '\u200a') || value == '\u2028' || value == '\u2029'
                || value == '\u202f' || value == '\u205f' || value == '\u3000' || value == '\ufeff';
    }

    /**
     * 原因完整保留；非法字符在任何落账之前拒绝。
     */
    public void reason(String value) {
        require(value != null && !value.isBlank() && value.length() <= 1000
                && value.codePoints().noneMatch(Character::isISOControl), "转换原因须为1至1000字且不含控制字符");
    }

    /**
     * 明确版本字段集内数字/时间/boolean为文本；实体增长不能偷偷扩旧V1，缺字段也拒绝。
     */
    public Map<String, String> facts(Object bean) {
        Map<String, String> result = new TreeMap<>();
        if (bean == null) return result;
        var expected = MesPhysicalTransformFactSchema.fields(bean.getClass());
        var values = BeanUtil.beanToMap(bean);
        require(values.keySet().containsAll(expected), "实体缺少原来源算法的明确字段，不能缩减旧事实");
        expected.forEach(key -> {
            Object value = values.get(key);
            if (value == null) result.put(key, null);
            else if (value instanceof BigDecimal number) result.put(key, number.toPlainString());
            else if (value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof TemporalAccessor || value instanceof Enum<?>) result.put(key, value.toString());
            else throw new CheckedException("来源事实字段" + key + "不是可冻结标量，禁止截断");
        });
        return result;
    }

    /**
     * 固定局部JSON写法；事实Map以TreeMap顺序提供，不依赖全局Jackson配置。
     */
    public String snapshot(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("实物转换证据无法完整序列化");
        }
    }

    /**
     * 带域版本前缀的UTF-8 SHA-256，不复用旧报工或SOP指纹语义。
     */
    public String fingerprint(String version, String snapshot) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((version + "|" + snapshot).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK不提供SHA-256", exception);
        }
    }

    private JsonNode tree(String source, int maxLength) {
        require(source != null && !source.isBlank() && source.length() <= maxLength, "转换JSON为空或超过完整文档限制");
        try {
            return json.readTree(source);
        } catch (Exception exception) {
            throw new CheckedException("转换JSON格式非法、字段重复或存在尾随内容");
        }
    }

    /**
     * 原冻结契约使用同一局部严格JSON读取，保留长整数，拒绝重复键及尾随内容。
     */
    public JsonNode objectDocument(String source, int maxLength) {
        JsonNode result = tree(source, maxLength);
        require(result != null && result.isObject(), "原转换配置/契约必须是完整JSON对象");
        return result;
    }

    private void fields(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject() && value.size() == expected.size(), "转换正文缺字段或包含未声明字段");
        expected.forEach(field -> require(value.has(field), "转换正文缺少字段" + field));
    }

    private void texts(JsonNode value, String... fields) {
        for (String field : fields)
            require(value.get(field).isTextual(), field + "必须为文本，不接number/boolean/null强转");
    }

    private void array(JsonNode value, String field, int min, int max) {
        JsonNode array = value.get(field);
        require(array.isArray() && array.size() >= min && array.size() <= max, field + "集合数量须为" + min + "至" + max);
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try {
            return json.treeToValue(value, type);
        } catch (Exception exception) {
            throw new CheckedException("转换正文类型不符合严格协议");
        }
    }
}
