package com.kitex.mom.mes.service.impl;

import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmPhysicalTransformStandardDTO;
import com.kitex.mom.mdm.feign.RemoteMdmPhysicalTransformStandardService;
import com.kitex.mom.mdm.support.MdmPhysicalTransformStandardCodec;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 只在未来新派工边界冻结真正已发布工程原版本；原任务查询只读永久原正文。
 * 工程发布不等于母件位置/库存锁及所有下游就绪，不放开当前非NONE发布门。
 */
@Component
@RequiredArgsConstructor
public class MesPhysicalTransformEngineeringFreezeService {
    private final RemoteMdmPhysicalTransformStandardService standards;
    private final MesPhysicalTransformCodec codec;
    private final MesActualFactoryAccessService access;

    /** 派工完整外层沿既有4Mi根协议；正文转义后的实际容量在写入前校验，不成功冻结坏契约。 */
    public String writableSnapshot(com.kitex.mom.mes.dto.MesExecutionContractDTO contract) {
        String raw = cn.hutool.json.JSONUtil.toJsonStr(contract);
        require(raw.length() <= 4 * 1024 * 1024, "完整派工契约（含工程原文转义）超过4Mi容量，未保存派工；请精简工程依据或明确升级契约容量");
        codec.objectDocument(raw, 4 * 1024 * 1024);
        return raw;
    }

    /** NONE不造资格；非NONE精确请求已发布版本并核实际任务路线、物料和工序。 */
    public MdmPhysicalTransformStandardDTO.Frozen freeze(MesTaskEntity task, MesTaskArrangeEntity arrange,
                                                        Long standardId, String configuration) {
        if (MesPhysicalTransformPolicyValidator.NONE.equals(configuration)) {
            require(standardId == null, "NONE模板不能挂正式转换工程版本");
            return null;
        }
        String factory = access.require("write");
        require(task != null && arrange != null && standardId != null && standardId > 0
                && Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(task.getFactoryCode(), factory) && Objects.equals(arrange.getFactoryCode(), factory)
                && Objects.equals(task.getCode(), arrange.getTaskCode()), "工程冻结缺真实工单、派工或精确标准");
        var query = new MdmPhysicalTransformStandardDTO.ResolveQuery();
        query.setStandardId(standardId.toString());
        query.setFactoryCode(factory);
        query.setMaterialCode(arrange.getMatCode());
        query.setProcessRouteId(task.getProcessRouteId() == null ? null : task.getProcessRouteId().toString());
        query.setProcessRouteCode(task.getProcessRouteCode());
        query.setProcessCode(arrange.getRouteCode());
        query.setPolicyJson(configuration);
        var response = standards.resolveReleased(query);
        require(response != null && response.isOk() && response.getData() != null, "MDM未返回精确已发布工程版本，禁止派工");
        var frozen = response.getData();
        var body = MdmPhysicalTransformStandardCodec.verifyFrozen(frozen);
        require(standardId.toString().equals(frozen.getStandardId()) && TenantContextHolder.getTenantId().toString().equals(frozen.getTenantId())
                && factory.equals(frozen.getFactoryCode()) && Objects.equals(query.getMaterialCode(), body.path("materialCode").textValue())
                && Objects.equals(query.getProcessRouteId(), body.path("processRouteId").textValue())
                && Objects.equals(query.getProcessRouteCode(), body.path("processRouteCode").textValue())
                && Objects.equals(query.getProcessCode(), body.path("processCode").textValue())
                && codec.objectDocument(configuration, 256 * 1024).equals(codec.objectDocument(body.path("policyJson").textValue(), 256 * 1024)),
                "MDM正式工程响应越界或与派工配置不一致");
        var dependency = codec.objectDocument(body.path("dependencySnapshot").textValue(), 4 * 1024 * 1024);
        require(task.getProcessRouteSnapshot() != null
                && task.getProcessRouteSnapshot().equals(dependency.path("route").path("procedureSnapshot").textValue()),
                "正式转换工程与原工单路线快照不同，须明确工程变更，不用当前路线覆盖原任务");
        return frozen;
    }

    /** 只读取原派工九文本字段发布证据；历史缺失为缺资格，不请求MDM补造。 */
    public MdmPhysicalTransformStandardDTO.Frozen readFrozen(MesTaskArrangeEntity arrange, String configuration) {
        if (arrange.getExecutionPolicySnapshot() == null || arrange.getExecutionPolicySnapshot().isBlank()) {
            require(MesPhysicalTransformPolicyValidator.NONE.equals(configuration), "旧空契约不能补造正式转换工程");
            return null;
        }
        var root = codec.objectDocument(arrange.getExecutionPolicySnapshot(), 4 * 1024 * 1024);
        var node = root.get("physicalTransformStandard");
        if (MesPhysicalTransformPolicyValidator.NONE.equals(configuration)) {
            require(node == null || node.isNull(), "历史NONE派工不能带工程资格");
            return null;
        }
        require(node != null && node.isObject() && node.size() == 9, "原派工缺正式工程发布依据，不从当前目录补造");
        for (String key : Set.of("version", "standardId", "tenantId", "factoryCode", "contentSnapshot", "contentFingerprint",
                "releasedById", "releasedBy", "releasedTime")) require(node.path(key).isTextual(), "原工程冻结字段必须是文本：" + key);
        var frozen = new MdmPhysicalTransformStandardDTO.Frozen();
        frozen.setVersion(node.path("version").textValue());
        frozen.setStandardId(node.path("standardId").textValue());
        frozen.setTenantId(node.path("tenantId").textValue());
        frozen.setFactoryCode(node.path("factoryCode").textValue());
        frozen.setContentSnapshot(node.path("contentSnapshot").textValue());
        frozen.setContentFingerprint(node.path("contentFingerprint").textValue());
        frozen.setReleasedById(node.path("releasedById").textValue());
        frozen.setReleasedBy(node.path("releasedBy").textValue());
        frozen.setReleasedTime(node.path("releasedTime").textValue());
        var body = MdmPhysicalTransformStandardCodec.verifyFrozen(frozen);
        String factory = access.require("read");
        require(Objects.equals(arrange.getTenantId(), TenantContextHolder.getTenantId()) && factory.equals(arrange.getFactoryCode())
                && frozen.getTenantId().equals(TenantContextHolder.getTenantId().toString()) && frozen.getFactoryCode().equals(factory)
                && Objects.equals(arrange.getMatCode(), body.path("materialCode").textValue())
                && Objects.equals(arrange.getRouteCode(), body.path("processCode").textValue())
                && codec.objectDocument(configuration, 256 * 1024).equals(codec.objectDocument(body.path("policyJson").textValue(), 256 * 1024)),
                "原正式工程依据不属于当前真实派工或政策");
        return frozen;
    }
}
