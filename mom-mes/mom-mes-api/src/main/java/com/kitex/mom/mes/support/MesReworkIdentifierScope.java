package com.kitex.mom.mes.support;

import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import lombok.Data;
import lombok.experimental.Accessors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 返工对原动态实例的冻结合同。它只授予返工识别权，不转移或修改原绑定生命周期。
 */
@Data
@Accessors(chain = true)
public class MesReworkIdentifierScope {
    public static final String VERSION = "MES_REWORK_IDENTIFIER_SCOPE_V1";
    public static final String NONE = "NONE";
    public static final String SOURCE_BINDINGS = "SOURCE_BINDINGS";

    private String version = VERSION;
    private String mode;
    private String tenantId;
    private String factoryCode;
    private String reworkTaskId;
    private String reworkTaskCode;
    private String sourceTaskId;
    private String sourceTaskCode;
    private String sourceProduceTaskCode;
    private String routeNodeCode;
    private List<Item> items = new ArrayList<>();

    public static MesReworkIdentifierScope parse(String raw) {
        try {
            MesReworkIdentifierScope scope = JSONUtil.toBean(raw, MesReworkIdentifierScope.class);
            require(scope != null && VERSION.equals(scope.version), "返工动态实例范围协议损坏或版本未知");
            return scope;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("返工动态实例范围JSON损坏");
        }
    }

    public static String encode(MesReworkIdentifierScope scope) {
        require(scope != null && VERSION.equals(scope.version), "返工动态实例范围版本无效");
        return JSONUtil.toJsonStr(scope);
    }

    public static String fingerprint(String raw) {
        require(raw != null && !raw.isBlank(), "返工动态实例范围原文不能为空");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算返工动态实例范围指纹", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    public void validateIdentity(Long tenant, String factory, Long reworkId, String reworkCode,
                                 Long sourceId, String sourceCode, String sourceOperation, String node) {
        require(Objects.equals(tenantId, Objects.toString(tenant, null))
                        && Objects.equals(factoryCode, factory)
                        && Objects.equals(reworkTaskId, Objects.toString(reworkId, null))
                        && Objects.equals(reworkTaskCode, reworkCode)
                        && Objects.equals(sourceTaskId, Objects.toString(sourceId, null))
                        && Objects.equals(sourceTaskCode, sourceCode)
                        && Objects.equals(sourceProduceTaskCode, sourceOperation)
                        && Objects.equals(routeNodeCode, node),
                "返工动态实例范围与原工单、原实际工序或稳定节点不一致");
        require(List.of(NONE, SOURCE_BINDINGS).contains(mode) && items != null,
                "返工动态实例范围模式无效");
        require((NONE.equals(mode) && items.isEmpty())
                        || (SOURCE_BINDINGS.equals(mode) && !items.isEmpty() && items.size() <= 500),
                "返工动态实例范围数量与模式不一致");
    }

    @Data
    @Accessors(chain = true)
    public static class Item {
        private String sourceBindingId;
        private String sourceBindingGroupId;
        private String sourceOperationId;
        private String sourceReportId;
        private String canonicalIdentifier;
        private String externalNamespace;
        private String externalCode;
        private String bindingStatus;
        private Integer bindingVersion;
        /**
         * ACTIVE_EXTERNAL或CANONICAL_ONLY。
         */
        private String inputMode;
        private String definitionFingerprint;
        private String graphFingerprint;
        private String sourceRouteFingerprint;
    }
}
