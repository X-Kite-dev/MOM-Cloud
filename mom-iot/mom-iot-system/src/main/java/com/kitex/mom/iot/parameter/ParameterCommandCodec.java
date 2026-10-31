package com.kitex.mom.iot.parameter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** IoT参数命令本域原件编码；不创建跨业务通用命令框架。 */
final class ParameterCommandCodec {
    private final ObjectMapper json;

    ParameterCommandCodec(ObjectMapper json) {
        this.json = json;
    }

    String encode(Object value) {
        try {
            String raw = json.writeValueAsString(value);
            require(raw.getBytes(StandardCharsets.UTF_8).length <= 65536, "参数命令原件超过64KiB");
            return raw;
        } catch (CheckedException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckedException("参数命令原件无法编码", e);
        }
    }

    <T> T decode(String raw, Class<T> type) {
        require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 65536, "参数原件缺失或超限");
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new CheckedException("参数命令原件损坏", e);
        }
    }

    Map<String, IotParameterCommandDTO.Readback> values(String raw) {
        try {
            return json.readValue(
                    raw, new TypeReference<TreeMap<String, IotParameterCommandDTO.Readback>>() {});
        } catch (Exception e) {
            throw new CheckedException("读回原文不可核验", e);
        }
    }

    static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void require(boolean yes, String message) {
        if (!yes) throw new CheckedException(message);
    }

    static void exact(String value, int limit) {
        require(
                value != null
                        && !value.isBlank()
                        && value.equals(value.trim())
                        && value.length() <= limit
                        && value.chars().noneMatch(Character::isISOControl),
                "命令范围、设备或版本编码缺失/非法");
    }

    static void requestId(String value) {
        require(
                value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                "请使用64字符以内的原请求号");
    }

    static BigDecimal decimal(String text) {
        require(
                text != null && text.length() <= 128 && text.matches("-?[0-9]+(?:\\.[0-9]+)?"),
                "参数必须是完整的原十进制文本");
        return new BigDecimal(text);
    }

    void verifyContext(
            MesParameterDispatchContextDTO.Evidence e, IotParameterCommandDTO.Prepare p) {
        require(
                e != null
                        && "MES_PARAMETER_DISPATCH@1".equals(e.getSchemaVersion())
                        && Objects.equals(e.getFactoryCode(), p.getFactoryCode())
                        && Objects.equals(e.getTaskCode(), p.getTaskCode())
                        && Objects.equals(e.getArrangeCode(), p.getArrangeCode())
                        && Objects.equals(e.getDeviceCode(), p.getDeviceCode()),
                "MES未确认同工厂/工单/派工/设备的冻结依据");
        exact(e.getOperationCode(), 100);
        exact(e.getProcessCode(), 100);
        exact(e.getStationCode(), 100);
        exact(e.getArrangeCode(), 100);
        require(
                e.getRouteNodeCode() != null
                        && e.getRouteNodeCode().matches("RN-[1-9][0-9]{0,18}")
                        && e.getEngineeringReleaseId() != null
                        && e.getEngineeringReleaseId().matches("[1-9][0-9]{0,18}")
                        && e.getEngineeringVersion() != null
                        && e.getEngineeringVersion() > 0
                        && e.getStandardVersion() != null
                        && e.getStandardVersion() > 0,
                "冻结节点或工程/参数版本缺失");
        for (String hash :
                List.of(
                        Objects.toString(e.getEngineeringHash(), ""),
                        Objects.toString(e.getSnapshotFingerprint(), ""),
                        Objects.toString(e.getNodeSnapshotFingerprint(), "")))
            require(hash.matches("[0-9a-f]{64}"), "冻结依据指纹不可核验");
        exact(e.getStandardNo(), 100);
        require(
                e.getParameters() != null
                        && !e.getParameters().isEmpty()
                        && e.getParameters().size() <= 100,
                "冻结参数须为1至100项");
        Set<String> identifiers = new HashSet<>();
        for (var item : e.getParameters()) {
            require(item != null, "冻结参数明细缺失");
            exact(item.identifier(), 100);
            exact(item.unit(), 40);
            require(identifiers.add(item.identifier()), "冻结参数重复");
            BigDecimal target = decimal(item.targetValue());
            require(
                    (item.lowerLimit() == null || target.compareTo(decimal(item.lowerLimit())) >= 0)
                            && (item.upperLimit() == null
                                    || target.compareTo(decimal(item.upperLimit())) <= 0),
                    "目标超出冻结上下限");
        }
    }
}
