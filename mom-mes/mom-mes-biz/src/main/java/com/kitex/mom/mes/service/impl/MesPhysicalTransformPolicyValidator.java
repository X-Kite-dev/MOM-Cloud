package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesPhysicalTransformPolicyContractDTO;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 严格公开转换配置与真实派工冻结证据；不持久化实体，不是IService CRUD服务。
 * 新字段缺失或历史NULL只表示NONE，未知或损坏配置绝不自动回退普通策略。
 */
@Component
@RequiredArgsConstructor
public class MesPhysicalTransformPolicyValidator {

    public static final String NONE = "{\"operationType\":\"NONE\"}";
    public static final String FROZEN_VERSION = "MES_PHYSICAL_FROZEN_POLICY_V1";

    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService factoryAccess;

    /** 草稿仅保存公开配置，不接受服务端模板身份、指纹或资格字段。 */
    public String normalize(String raw, MesExecutionTemplateEntity template) {
        String normalized = configuration(raw);
        if (!NONE.equals(normalized)) {
            var policy = codec.policy(normalized);
            codec.code(template.getMaterialCode(), "转换模板实际物料", 100);
            codec.code(template.getRouteCode(), "转换模板实际工序", 100);
            assertMode(template.getReportMode(), policy.getInputObjectType());
        }
        return normalized;
    }

    /** 原算法、公开配置及SHA完整冻结；不把该证据当成工艺/库存执行资格。 */
    public MesPhysicalTransformPolicyContractDTO freeze(String normalizedConfiguration) {
        String normalized = configuration(normalizedConfiguration);
        var contract = new MesPhysicalTransformPolicyContractDTO();
        contract.setVersion(FROZEN_VERSION);
        contract.setConfigurationJson(normalized);
        contract.setConfigurationFingerprint(codec.fingerprint(FROZEN_VERSION, normalized));
        return contract;
    }

    /** 只读原派工完整快照，不查询当前模板来补造历史政策。 */
    public MesPhysicalTransformPolicyContractDTO readFrozen(MesTaskArrangeEntity arrange) {
        require(arrange != null, "缺少原派工，不能读取转换政策");
        if (arrange.getExecutionPolicySnapshot() == null || arrange.getExecutionPolicySnapshot().isBlank())
            return freeze(NONE);
        JsonNode root = codec.objectDocument(arrange.getExecutionPolicySnapshot(), 4 * 1024 * 1024);
        JsonNode frozen = root.get("physicalTransformPolicy");
        if (frozen == null || frozen.isNull()) return freeze(NONE);
        require(frozen.isObject() && frozen.size() == 3
                && frozen.path("version").isTextual() && frozen.path("configurationJson").isTextual()
                && frozen.path("configurationFingerprint").isTextual(), "原派工转换政策冻结结构损坏，禁止补造");
        require(FROZEN_VERSION.equals(frozen.path("version").textValue()), "原派工转换政策算法未知，不能改用当前算法");
        String raw = frozen.path("configurationJson").textValue();
        var actual = freeze(raw);
        require(actual.getConfigurationJson().equals(raw)
                && actual.getConfigurationFingerprint().equals(frozen.path("configurationFingerprint").textValue()),
                "原派工转换配置正文或指纹不一致，不能重新规范化覆盖原事实");
        if (!NONE.equals(raw)) {
            String factory = factoryAccess.require("read");
            require(Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
                    && Objects.equals(arrange.getFactoryCode(), factory), "转换政策不属于实际租户工厂");
            require(root.path("templateFrozen").isBoolean() && root.path("templateFrozen").booleanValue()
                    && arrange.getExecutionTemplateId() != null && arrange.getExecutionTemplateVersion() != null
                    && arrange.getExecutionTemplateVersion() > 0, "非NONE转换配置必须来自真实模板派工冻结");
            JsonNode templateId = root.path("templateId");
            require((templateId.isTextual() || templateId.isIntegralNumber())
                    && arrange.getExecutionTemplateId().toString().equals(templateId.asText())
                    && root.path("templateCode").isTextual()
                    && Objects.equals(arrange.getExecutionTemplateCode(), root.path("templateCode").textValue())
                    && root.path("templateVersion").isIntegralNumber()
                    && root.path("templateVersion").canConvertToInt()
                    && arrange.getExecutionTemplateVersion() == root.path("templateVersion").intValue(),
                    "转换原模板身份/版本与派工不一致");
            codec.code(arrange.getMatCode(), "转换原物料", 100);
            codec.code(arrange.getRouteCode(), "转换原工序", 100);
            require(root.path("reportMode").isTextual()
                    && Objects.equals(arrange.getReportMode(), root.path("reportMode").textValue()), "转换原报工对象模式与派工不一致");
            assertMode(arrange.getReportMode(), codec.policy(raw).getInputObjectType());
        }
        return actual;
    }

    /** 完整执行/下游/三端未贯通期间，只允许NONE发布或派工，不提供本地true旁路。 */
    public void assertPublicationAvailable(String configuration) {
        require(NONE.equals(configuration(configuration)),
                "实物转换的永久库存事务、完整谱系/下游保护和三端尚未贯通；当前只可保存转换草稿，不能发布或派工");
    }

    /** NULL是历史未启用；显式空串、未知动作/版本、重复/额外字段必须拒绝。 */
    private String configuration(String raw) {
        if (raw == null) return NONE;
        JsonNode root = codec.objectDocument(raw, 256 * 1024);
        if (root.size() == 1 && root.path("operationType").isTextual()
                && "NONE".equals(root.path("operationType").textValue())) return NONE;
        var policy = codec.policy(raw);
        policy.setEquivalenceFactor(codec.amount(codec.quantity(policy.getEquivalenceFactor(), false, "当量系数")));
        return codec.snapshot(policy);
    }

    /** 模式是原来源类型，不增加第六种假报工模式，也不将包装拆箱混入实物转换。 */
    private void assertMode(String mode, String inputObject) {
        boolean matched = "QUANTITY".equals(mode) && "BATCH".equals(inputObject)
                || "ONE_CODE".equals(mode) && "SN".equals(inputObject)
                || "TRANSFER_CARD".equals(mode) && Set.of("TRAVELER", "PANEL").contains(inputObject);
        require(matched, "转换投入对象与模板/原派工报工模式不一致；包装沿原拆箱重组业务");
    }
}
