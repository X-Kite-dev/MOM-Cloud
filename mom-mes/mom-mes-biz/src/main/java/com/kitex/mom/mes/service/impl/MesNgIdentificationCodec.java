package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 不良报工的原始输入与规范标识证据。仅校验当时冻结事实，不查询当前映射或增加使用次数。
 * SOURCE_V1、隔离采集和原请求正文各自保持原协议，避免升级改变旧账的解释及SHA。
 */
@Component
@RequiredArgsConstructor
public class MesNgIdentificationCodec {
    public static final String VERSION = "MES_NG_IDENTIFICATION_V1";
    private static final String RESOLUTION_VERSION = "MES_IDENTIFIER_RESOLUTION_V1";
    private static final String REWORK_RESOLUTION_VERSION = "MES_REWORK_IDENTIFIER_RESOLUTION_V1";
    private static final int MAX_DOCUMENT = 64 * 1024;
    private static final Set<String> RESOLUTION_FIELDS = Set.of("version", "submittedIdentifier",
            "canonicalIdentifier", "objectType", "resolveMode", "identifierType");
    private static final Set<String> MAPPED_FIELDS = Set.of("mappingId", "mappingCode", "mappingVersion", "scope");
    private final MesPhysicalTransformCodec codec;

    /** SOURCE_V1已生成后，仅为真实新报告冻结一次；历史读取绝不调用。 */
    public void freeze(MesTaskReportUnqualifiedEntity row) {
        require(row.getIdentificationVersion() == null && row.getIdentificationSnapshot() == null
                && row.getIdentificationFingerprint() == null, "新不良标识证据不能覆盖已有原文");
        row.setIdentificationVersion(VERSION);
        String raw = codec.snapshot(facts(row));
        codec.objectDocument(raw, MAX_DOCUMENT);
        row.setIdentificationSnapshot(raw);
        row.setIdentificationFingerprint(codec.fingerprint(VERSION, raw));
        verify(row);
    }

    /** 历史全NULL保持旧含义；部分字段缺失、未知协议或与原报告不一致均不得降级兼容。 */
    public void verify(MesTaskReportUnqualifiedEntity row) {
        if (row.getIdentificationVersion() == null) {
            require(row.getIdentificationSnapshot() == null && row.getIdentificationFingerprint() == null
                    && row.getSubmittedIdentifier() == null && row.getIdentifierMappingId() == null
                    && row.getIdentifierType() == null && row.getIdentifierResolutionSnapshot() == null,
                    "不良标识证明不完整，不能当作旧历史");
            return;
        }
        require(VERSION.equals(row.getIdentificationVersion()) && row.getIdentificationSnapshot() != null
                && Objects.equals(row.getIdentificationFingerprint(),
                codec.fingerprint(VERSION, row.getIdentificationSnapshot())), "不良标识证据版本或SHA不一致");
        var actual = codec.objectDocument(row.getIdentificationSnapshot(), MAX_DOCUMENT);
        var expected = facts(row);
        require(actual.size() == expected.size() && expected.keySet().stream().allMatch(actual::has),
                "不良标识证据存在遗漏或未知字段");
        expected.forEach((key, value) -> require(value == null ? actual.get(key).isNull()
                : actual.get(key).isTextual() && value.equals(actual.get(key).textValue()),
                "不良标识原字段不一致：" + key));
    }

    /** 新协议按原始输入恢复；两个历史分支完全保留原先精确/去空白语义。 */
    public static boolean sameInput(MesTaskReportUnqualifiedEntity row, String submitted) {
        if (row.getIdentificationVersion() != null) {
            return Objects.equals(row.getSubmittedIdentifier(), original(submitted));
        }
        return row.getSourceVersion() == null
                ? Objects.equals(StrUtil.nullToEmpty(row.getTransferCode()).trim(), StrUtil.nullToEmpty(submitted).trim())
                : Objects.equals(row.getTransferCode(), original(submitted));
    }

    /** 延续原NG的blank-to-null契约，非空值不擅自去掉外侧空白。 */
    public static String original(String value) {
        return StrUtil.blankToDefault(value, null);
    }

    private TreeMap<String, String> facts(MesTaskReportUnqualifiedEntity row) {
        require(MesProductionNgSourceCodec.VERSION.equals(row.getSourceVersion())
                && row.getSourceFingerprint() != null && row.getId() != null && row.getTenantId() != null,
                "双标识证据必须绑定真实已冻结不良来源");
        String mappingVersion = resolutionVersion(row);
        var result = new TreeMap<String, String>();
        result.put("version", VERSION);
        result.put("reportId", row.getId().toString());
        result.put("requestId", row.getRequestId());
        result.put("tenantId", row.getTenantId().toString());
        result.put("factoryCode", row.getFactoryCode());
        result.put("sourceFingerprint", row.getSourceFingerprint());
        result.put("originalIdentifier", row.getSubmittedIdentifier());
        result.put("canonicalIdentifier", row.getTransferCode());
        result.put("mappingId", row.getIdentifierMappingId() == null ? null : row.getIdentifierMappingId().toString());
        result.put("mappingVersion", mappingVersion);
        result.put("identifierType", row.getIdentifierType());
        result.put("resolutionSnapshot", row.getIdentifierResolutionSnapshot());
        return result;
    }

    /** 内部解析器仍使用原数值ID格式；后端精确读Long，外层始终输出文本供三端使用。 */
    private String resolutionVersion(MesTaskReportUnqualifiedEntity row) {
        String original = row.getSubmittedIdentifier();
        if (original == null) {
            require(ReportModeEnum.QUANTITY.name().equals(row.getReportMode())
                    && row.getTransferCode() == null && row.getIdentifierMappingId() == null
                    && row.getIdentifierType() == null && row.getIdentifierResolutionSnapshot() == null,
                    "无码不良报告不能附带标识或映射事实");
            return null;
        }
        require(!original.isBlank() && original.length() <= 200, "原始不良标识不能为空白或超过200字符");
        codec.code(row.getTransferCode(), "规范不良标识", 200);
        var raw = codec.objectDocument(row.getIdentifierResolutionSnapshot(), MAX_DOCUMENT);
        if (REWORK_RESOLUTION_VERSION.equals(raw.path("version").asText())) {
            return reworkResolutionVersion(row, original, raw);
        }
        boolean mapped = row.getIdentifierMappingId() != null;
        var keys = new HashSet<>(RESOLUTION_FIELDS);
        if (mapped) keys.addAll(MAPPED_FIELDS);
        require(raw.size() == keys.size() && keys.stream().allMatch(raw::has), "不良原标识解析字段不完整");
        require(RESOLUTION_VERSION.equals(text(raw, "version"))
                && original.trim().equals(text(raw, "submittedIdentifier"))
                && row.getTransferCode().equals(text(raw, "canonicalIdentifier"))
                && Objects.equals(row.getIdentifierType(), text(raw, "identifierType")), "不良原始与规范标识解析不一致");
        require(Set.of("CANONICAL", "TEMPORARY", "ALTERNATE", "EXTERNAL", "CUSTOMER", "SUPPLIER", "LEGACY")
                .contains(row.getIdentifierType()), "不良原标识类型未知");
        String objectType = switch (ReportModeEnum.fromCode(row.getReportMode())) {
            case ONE_CODE -> "UNIT";
            case TRANSFER_CARD -> "TRAVELER";
            case QUANTITY -> "BATCH";
            case PACKAGE_ONE_CODE, PACKAGE_QUANTITY -> "PACKAGE";
        };
        require(objectType.equals(text(raw, "objectType")) && Set.of("ALLOW_MAPPING", "CANONICAL_ONLY", "MAPPING_REQUIRED")
                .contains(text(raw, "resolveMode")), "不良解析对象模式或策略不一致");
        if (!mapped) {
            require("CANONICAL".equals(row.getIdentifierType()) && original.trim().equals(row.getTransferCode())
                    && !"MAPPING_REQUIRED".equals(text(raw, "resolveMode")), "规范码解析不能伪装成映射成功");
            return null;
        }
        require(row.getIdentifierMappingId() > 0 && raw.get("mappingId").isIntegralNumber()
                && !"CANONICAL".equals(row.getIdentifierType())
                && raw.get("mappingId").canConvertToLong()
                && raw.get("mappingId").longValue() == row.getIdentifierMappingId()
                && raw.get("mappingVersion").isIntegralNumber() && raw.get("mappingVersion").canConvertToInt()
                && raw.get("mappingVersion").intValue() > 0
                && !"CANONICAL_ONLY".equals(text(raw, "resolveMode")), "不良原映射ID/版本或策略不一致");
        codec.code(text(raw, "mappingCode"), "原映射编码", 100);
        JsonNode scope = raw.get("scope");
        var scopeKeys = Set.of("materialCode", "taskCode", "routeCode", "worksiteCode");
        require(scope.isObject() && scope.size() == 4 && scopeKeys.stream().allMatch(key -> scope.path(key).isTextual()),
                "不良原映射作用域必须完整保留");
        return raw.get("mappingVersion").asText();
    }

    /** 返工动态实例是批准范围引用，不是静态映射，也不创建第二套外码生命周期。 */
    private String reworkResolutionVersion(MesTaskReportUnqualifiedEntity row, String original, JsonNode raw) {
        var keys = Set.of("version", "submittedIdentifier", "canonicalIdentifier", "objectType",
                "identifierType", "sourceBindingId", "sourceBindingGroupId", "sourceBindingVersion",
                "inputMode", "scopeFingerprint", "sourceOperationId", "sourceReportId");
        require(raw.size() == keys.size() && keys.stream().allMatch(raw::has),
                "返工不良原实例解析字段不完整");
        require(row.getIdentifierMappingId() == null
                        && "ONE_CODE".equals(row.getReportMode())
                        && "REWORK_SOURCE_BINDING".equals(row.getIdentifierType())
                        && "REWORK_SOURCE_BINDING".equals(text(raw, "identifierType"))
                        && "UNIT".equals(text(raw, "objectType"))
                        && original.trim().equals(text(raw, "submittedIdentifier"))
                        && row.getTransferCode().equals(text(raw, "canonicalIdentifier"))
                        && Set.of("ACTIVE_EXTERNAL", "CANONICAL_ONLY").contains(text(raw, "inputMode"))
                        && raw.path("scopeFingerprint").isTextual()
                        && raw.path("scopeFingerprint").asText().matches("[0-9a-f]{64}"),
                "返工不良原实例解析与报告对象、原码或批准范围不一致");
        for (String key : Set.of("sourceBindingId", "sourceBindingGroupId", "sourceOperationId", "sourceReportId")) {
            require(raw.path(key).isTextual() && raw.path(key).asText().matches("[1-9][0-9]{0,18}"),
                    "返工不良原实例引用格式无效：" + key);
        }
        require(raw.path("sourceBindingVersion").isIntegralNumber()
                        && raw.path("sourceBindingVersion").canConvertToInt()
                        && raw.path("sourceBindingVersion").intValue() > 0,
                "返工不良原实例版本无效");
        return raw.path("sourceBindingVersion").asText();
    }

    private String text(JsonNode raw, String key) {
        require(raw.path(key).isTextual(), "不良标识解析字段必须为文本：" + key);
        return raw.get(key).textValue();
    }
}
