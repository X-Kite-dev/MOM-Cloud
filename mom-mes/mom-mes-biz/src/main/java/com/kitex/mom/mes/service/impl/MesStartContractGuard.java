package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 开工与准备观察共用派工出生时的资源要求，不读取今日模板，也不替代设备资格和工装扫码门。
 */
@Service
@RequiredArgsConstructor
public class MesStartContractGuard {

    private final MesExecutionTemplateService executionTemplates;
    private final MesPhysicalTransformCodec codec;

    public void assertDeviceBound(MesTaskArrangeEntity arrange) {
        var contract = frozenContract(arrange);
        require(!Boolean.TRUE.equals(contract.getDeviceRequired()) || StrUtil.isNotBlank(arrange.getPlannedDeviceCode()),
                "冻结执行模板要求设备校验，但派工未冻结计划设备，禁止开工/复工");
    }

    public void assertToolingBound(MesTaskArrangeEntity arrange) {
        var contract = frozenContract(arrange);
        boolean hasTooling = StrUtil.isNotBlank(arrange.getPlannedToolingCodes())
                && Arrays.stream(arrange.getPlannedToolingCodes().split(","))
                .anyMatch(StrUtil::isNotBlank);
        require(!Boolean.TRUE.equals(contract.getToolingRequired()) || hasTooling,
                "冻结执行模板要求工装校验，但派工未冻结计划工装，禁止开工/复工");
    }

    private MesExecutionContractDTO frozenContract(MesTaskArrangeEntity arrange) {
        require(arrange != null, "开工契约的原派工缺失");
        String snapshot = arrange.getExecutionPolicySnapshot();
        if (StrUtil.isBlank(snapshot)) {
            require(legacyIdentity(arrange),
                    "派工已记录冻结模板身份但开工契约缺失，禁止按历史默认放行");
            return executionTemplates.contract(arrange);
        }

        JsonNode root;
        try {
            root = codec.objectDocument(snapshot, 4 * 1024 * 1024);
        } catch (CheckedException failure) {
            throw new CheckedException("派工开工契约损坏：" + failure.getMessage());
        }
        require(root.path("templateFrozen").isBoolean() && root.path("deviceRequired").isBoolean()
                && root.path("toolingRequired").isBoolean(), "派工开工契约资源要求结构损坏，禁止猜测默认值");
        if (root.path("templateFrozen").booleanValue()) {
            JsonNode templateId = root.path("templateId");
            require(arrange.getExecutionTemplateId() != null && arrange.getExecutionTemplateId() > 0
                            && arrange.getExecutionTemplateVersion() != null && arrange.getExecutionTemplateVersion() > 0
                            && (templateId.isTextual() || templateId.isIntegralNumber())
                            && arrange.getExecutionTemplateId().toString().equals(templateId.asText())
                            && root.path("templateCode").isTextual()
                            && StrUtil.isNotBlank(arrange.getExecutionTemplateCode())
                            && Objects.equals(arrange.getExecutionTemplateCode(), root.path("templateCode").textValue())
                            && root.path("templateVersion").isIntegralNumber()
                            && root.path("templateVersion").canConvertToInt()
                            && arrange.getExecutionTemplateVersion() == root.path("templateVersion").intValue(),
                    "派工开工契约原模板身份或版本不一致，证据不可核验");
        } else {
            require(legacyIdentity(arrange)
                            && (root.path("templateId").isMissingNode() || root.path("templateId").isNull())
                            && root.path("templateVersion").isIntegralNumber() && root.path("templateVersion").canConvertToInt()
                            && root.path("templateVersion").intValue() == 0
                            && "LEGACY_ROUTE".equals(root.path("templateCode").textValue()),
                    "派工开工契约历史兼容身份未知，不能覆盖原冻结模板");
        }
        return executionTemplates.contract(arrange);
    }

    private boolean legacyIdentity(MesTaskArrangeEntity arrange) {
        return arrange.getExecutionTemplateId() == null
                && (arrange.getExecutionTemplateVersion() == null || arrange.getExecutionTemplateVersion() == 0)
                && (StrUtil.isBlank(arrange.getExecutionTemplateCode())
                || "LEGACY_ROUTE".equals(arrange.getExecutionTemplateCode()));
    }
}
