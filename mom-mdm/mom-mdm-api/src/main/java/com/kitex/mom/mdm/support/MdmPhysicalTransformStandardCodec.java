package com.kitex.mom.mdm.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** 转换工程标准严格文本协议；无Spring状态，可供MDM发布和MES原内容核验共用。 */
public final class MdmPhysicalTransformStandardCodec {
    public static final String VERSION = "MES_PHYSICAL_ENGINEERING_V1";
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .addModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance)
                    .addSerializer(Long.TYPE, ToStringSerializer.instance))
            .findAndAddModules().build();

    private MdmPhysicalTransformStandardCodec() {
    }

    /** 根和嵌套重复键、尾随内容均拒绝，原文不能由宽松解析器修补。 */
    public static JsonNode object(String raw, int limit) {
        require(raw != null && !raw.isBlank() && raw.length() <= limit, "工程JSON为空或超过容量");
        try {
            JsonNode node = JSON.readTree(raw);
            require(node != null && node.isObject(), "工程JSON根必须是对象");
            return node;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("工程JSON重复字段、类型或尾随内容不合法");
        }
    }

    /** 草稿只接受十五项公开文本；显式复制仍核原版本，期望组织不作为归属来源。 */
    public static MdmPhysicalTransformStandardDTO.Draft draft(String raw) {
        JsonNode root = object(raw, 512 * 1024);
        texts(root, Set.of("expectedTenantId", "expectedFactoryCode", "id", "copySourceId", "expectedFingerprint", "expectedOperationId", "standardNo", "standardName", "materialCode",
                "processRouteId", "processCode", "unitConversionId", "positionLayoutCode", "policyJson", "reason"));
        return convert(root, MdmPhysicalTransformStandardDTO.Draft.class);
    }

    /** 状态动作只接受七项文本；每次绑定期望组织、原内容SHA及原动作身份。 */
    public static MdmPhysicalTransformStandardDTO.Action action(String raw) {
        JsonNode root = object(raw, 16 * 1024);
        texts(root, Set.of("expectedTenantId", "expectedFactoryCode", "id", "expectedFingerprint", "expectedOperationId", "action", "reason"));
        var command = convert(root, MdmPhysicalTransformStandardDTO.Action.class);
        id(command.getId());
        id(command.getExpectedOperationId());
        require(command.getExpectedFingerprint().matches("[0-9a-f]{64}"), "期望工程指纹格式不正确");
        require(Set.of("SUBMIT", "WITHDRAW", "REJECT", "RELEASE", "RETIRE", "COPY").contains(command.getAction()), "未知工程动作");
        reason(command.getReason());
        return command;
    }

    /** 原派工或内部响应的工程证据：完整已发布身份及十五文本字段正文，不从当前MDM补造。 */
    public static JsonNode verifyFrozen(MdmPhysicalTransformStandardDTO.Frozen frozen) {
        require(frozen != null && VERSION.equals(frozen.getVersion()), "缺少或未知已冻结工程版本");
        id(frozen.getStandardId());
        id(frozen.getTenantId());
        code(frozen.getFactoryCode(), 100, "工程冻结工厂");
        id(frozen.getReleasedById());
        code(frozen.getReleasedBy(), 100, "工程发布账号");
        try {
            require(frozen.getReleasedTime() != null, "工程发布时间缺失");
            java.time.LocalDateTime.parse(frozen.getReleasedTime());
        } catch (Exception exception) {
            throw new CheckedException("工程首次发布时间格式损坏");
        }
        require(frozen.getContentFingerprint() != null && frozen.getContentFingerprint().matches("[0-9a-f]{64}")
                && frozen.getContentFingerprint().equals(fingerprint(VERSION, frozen.getContentSnapshot())), "工程冻结原内容指纹损坏");
        JsonNode body = object(frozen.getContentSnapshot(), 4 * 1024 * 1024);
        texts(body, Set.of("version", "tenantId", "factoryCode", "standardId", "standardNo", "standardName", "versionNo",
                "materialCode", "processRouteId", "processRouteCode", "processCode", "positionLayoutCode", "policyJson",
                "dependencySnapshot", "dependencyFingerprint"));
        require(VERSION.equals(body.path("version").textValue()) && frozen.getStandardId().equals(body.path("standardId").textValue())
                && frozen.getTenantId().equals(body.path("tenantId").textValue())
                && frozen.getFactoryCode().equals(body.path("factoryCode").textValue()), "工程正文与冻结身份不一致");
        id(body.path("processRouteId").textValue());
        id(body.path("versionNo").textValue());
        policy(body.path("policyJson").textValue());
        object(body.path("dependencySnapshot").textValue(), 4 * 1024 * 1024);
        require(body.path("dependencyFingerprint").textValue().equals(fingerprint("MDM_PHYSICAL_DEPENDENCIES_V1",
                body.path("dependencySnapshot").textValue())), "工程原依赖正文SHA不一致");
        return body;
    }

    /** 九字段与MES数学政策一致；当前不将NONE登记为工程标准。 */
    public static String policy(String raw) {
        JsonNode root = object(raw, 256 * 1024);
        Set<String> stringKeys = Set.of("version", "operationType", "inputObjectType", "outputObjectType",
                "inputUnit", "outputUnit", "equivalenceFactor");
        require(root.size() == 9 && root.has("partialInputAllowed") && root.has("positionCodes"), "转换政策字段不完整或含未知字段");
        for (String key : stringKeys) require(root.path(key).isTextual(), "转换政策必须是文本：" + key);
        require(root.path("partialInputAllowed").isBoolean() && root.path("positionCodes").isArray(), "部分投入/位置集合类型不正确");
        require("MES_PHYSICAL_TRANSFORM_POLICY_V1".equals(root.path("version").textValue()), "转换数学政策版本未知");
        require(Set.of("SPLIT", "MERGE", "REBATCH", "DEPANEL").contains(root.path("operationType").textValue()), "转换动作未知");
        require(Set.of("BATCH", "SN", "TRAVELER", "PANEL").contains(root.path("inputObjectType").textValue())
                && Set.of("BATCH", "SN", "TRAVELER").contains(root.path("outputObjectType").textValue()), "转换对象类型未知");
        code(root.path("inputUnit").textValue(), 100, "投入单位");
        code(root.path("outputUnit").textValue(), 100, "产出单位");
        BigDecimal factor = quantity(root.path("equivalenceFactor").textValue());
        require(!root.path("inputUnit").textValue().equals(root.path("outputUnit").textValue()) || factor.compareTo(BigDecimal.ONE) == 0,
                "同单位转换系数必须为1");
        boolean panel = "DEPANEL".equals(root.path("operationType").textValue());
        require(panel ? "PANEL".equals(root.path("inputObjectType").textValue()) && "SN".equals(root.path("outputObjectType").textValue())
                : !"PANEL".equals(root.path("inputObjectType").textValue()), "拆板及母对象类型不一致");
        require(panel ? root.path("positionCodes").size() >= 1 && root.path("positionCodes").size() <= 1000
                : root.path("positionCodes").isEmpty(), "工程完整位置集合不符合动作");
        Set<String> positions = new HashSet<>();
        for (JsonNode position : root.path("positionCodes")) {
            require(position.isTextual(), "工程位置编码必须是文本");
            require(positions.add(code(position.textValue(), 100, "工程位置")), "工程位置不能重复");
        }
        if (panel) require(factor.stripTrailingZeros().scale() <= 0 && factor.compareTo(BigDecimal.valueOf(positions.size())) <= 0,
                "拆板一母单位须为完整SN当量且不超过完整位置数量");
        require(!"SN".equals(root.path("inputObjectType").textValue()) || !root.path("partialInputAllowed").booleanValue(),
                "SN不允许部分投入");
        require(!panel || !root.path("partialInputAllowed").booleanValue() || factor.compareTo(BigDecimal.ONE) == 0,
                "部分拆板须沿同单位位置余额，不能投入分数面板");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("equivalenceFactor", amount(factor));
        return snapshot(root);
    }

    /** 无浮点/指数/符号/舍入的精确数量。 */
    public static BigDecimal quantity(String value) {
        require(value != null && value.length() <= 40 && value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,8})?"), "工程数量须为普通正十进制文本");
        BigDecimal amount = new BigDecimal(value);
        require(amount.signum() > 0 && amount.precision() <= 28, "工程数量须大于0且最多28位有效数字");
        return amount;
    }

    /** 精确表示，不截断到数据库猜测精度。 */
    public static String amount(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        require(normalized.precision() + Math.max(0, -normalized.scale()) <= 28 && Math.max(0, normalized.scale()) <= 8,
                "工程当量不能精确表示，禁止舍入");
        return normalized.toPlainString();
    }

    /** 正Long文本，不接受客户端JSON number。 */
    public static Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "工程身份必须为正Long文本");
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new CheckedException("工程身份超过Long容量");
        }
    }

    /** 原编码首尾空白及控制字符拒绝，不清洗标识。 */
    public static String code(String value, int limit, String label) {
        require(value != null && !value.isBlank() && value.length() <= limit
                && !edgeBlank(value.codePointAt(0)) && !edgeBlank(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(c -> c <= 31 || c >= 127 && c <= 159), label + "为空、过长或带控制/边界空白");
        return value;
    }

    /** 明确保留人工说明，不静默裁剪。 */
    public static void reason(String value) {
        require(value != null && !value.isBlank() && value.length() <= 1000
                && value.codePoints().noneMatch(c -> c <= 31 || c >= 127 && c <= 159), "工程原因必须明确且不超过1000位/不含控制字符");
    }

    /** 稳定工程正文；不受服务全局Long序列化或日期输出配置影响。 */
    public static String snapshot(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("无法生成完整工程正文");
        }
    }

    /** 领域回读使用树比较，不将两份完整履历再转义成第三层字符串而引入隐藏总容量。 */
    public static com.fasterxml.jackson.databind.node.ObjectNode entityFacts(Object value) {
        try {
            JsonNode root = JSON.valueToTree(value);
            require(root.isObject(), "工程领域事实必须是实体对象");
            return (com.fasterxml.jackson.databind.node.ObjectNode) root;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("无法核对完整工程领域事实");
        }
    }

    /** 全文版本域SHA，不从当前依赖修补旧发布正文。 */
    public static String fingerprint(String version, String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((version + "|" + raw).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成工程正文指纹", exception);
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private static boolean edgeBlank(int code) {
        return code == 32 || code == 160 || code == 5760 || code >= 8192 && code <= 8202
                || code == 8232 || code == 8233 || code == 8239 || code == 8287 || code == 12288 || code == 65279;
    }

    private static void texts(JsonNode root, Set<String> fields) {
        require(root.size() == fields.size(), "工程正文缺字段或包含未知字段");
        for (String field : fields) require(root.path(field).isTextual(), "工程正文必须是文本：" + field);
    }

    private static <T> T convert(JsonNode root, Class<T> type) {
        try {
            return JSON.treeToValue(root, type);
        } catch (Exception exception) {
            throw new CheckedException("工程正文类型不合法");
        }
    }
}
