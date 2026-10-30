package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.dto.MesPhysicalTransformExecutionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 正式执行确认的严格文本协议；不接库存、工程、计算结果或客户端成功状态。
 */
@Component
@RequiredArgsConstructor
public class MesPhysicalTransformExecutionCodec {
    public static final String VERSION = "MES_PHYSICAL_TRANSFORM_APPLY_V1";
    public static final String EXECUTION_VERSION = "MES_PHYSICAL_TRANSFORM_EXECUTION_V1";
    public static final String CALCULATION_VERSION = "MES_PHYSICAL_TRANSFORM_CALCULATION_V1";
    public static final String RECEIPT_VERSION = "MES_PHYSICAL_TRANSFORM_RECEIPT_V1";
    private final MesPhysicalTransformCodec codec;

    public MesPhysicalTransformExecutionDTO.Apply apply(String rawJson) {
        var root = codec.objectDocument(rawJson, 16 * 1024);
        var found = new TreeSet<String>();
        root.fieldNames().forEachRemaining(found::add);
        var expected = Set.of("version", "expectedTenantId", "expectedFactoryCode", "expectedActorUserId",
                "expectedCommandFingerprint", "expectedHoldFingerprint", "positionSurveyId",
                "positionSurveyFingerprint", "reason");
        require(found.equals(expected), "正式转换执行正文存在缺失或未知字段");
        expected.forEach(field -> require(root.path(field).isTextual(), field + "必须是文本，不能隐式强转"));
        require(VERSION.equals(root.path("version").textValue()), "正式转换执行协议版本未知");
        var result = new MesPhysicalTransformExecutionDTO.Apply();
        result.setVersion(VERSION);
        result.setExpectedTenantId(root.path("expectedTenantId").textValue());
        result.setExpectedFactoryCode(root.path("expectedFactoryCode").textValue());
        result.setExpectedActorUserId(root.path("expectedActorUserId").textValue());
        result.setExpectedCommandFingerprint(sha(root.path("expectedCommandFingerprint").textValue(), "原命令SHA"));
        result.setExpectedHoldFingerprint(sha(root.path("expectedHoldFingerprint").textValue(), "原占用SHA"));
        result.setPositionSurveyId(root.path("positionSurveyId").textValue());
        result.setPositionSurveyFingerprint(root.path("positionSurveyFingerprint").textValue());
        codec.id(result.getExpectedTenantId());
        codec.id(result.getExpectedActorUserId());
        codec.code(result.getExpectedFactoryCode(), "期望工厂", 100);
        codec.reason(root.path("reason").textValue());
        result.setReason(root.path("reason").textValue());
        if (!result.getPositionSurveyId().isEmpty()) codec.id(result.getPositionSurveyId());
        if (!result.getPositionSurveyFingerprint().isEmpty())
            sha(result.getPositionSurveyFingerprint(), "原位置采集SHA");
        return result;
    }

    private String sha(String value, String label) {
        require(value != null && value.matches("[0-9a-f]{64}"), label + "须为SHA-256文本");
        return value;
    }
}
