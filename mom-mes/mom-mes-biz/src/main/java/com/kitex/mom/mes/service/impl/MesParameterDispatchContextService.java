package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.dto.MesParameterDispatchContextDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWorksiteMoldEntity;
import com.kitex.mom.mes.mapper.MesProduceTaskMapper;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesWorksiteMoldMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** MES拥有冻结生产依据；IoT拥有设备命令，不把计划设备当作现场绑定证明。 */
@Service
@RequiredArgsConstructor
public class MesParameterDispatchContextService {
    private final MesActualFactoryAccessService access;
    private final MesTaskMapper tasks;
    private final MesTaskArrangeMapper arrangements;
    private final MesProduceTaskMapper operations;
    private final MesWorksiteMoldMapper bindings;
    private final MesProcessParameterStandardFreezeService freeze;
    private final MesEngineeringPackageService engineering;
    private final MesReworkMaterialPlanService reworkPlans;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesParameterDispatchContextDTO.Evidence query(MesParameterDispatchContextDTO.Query q) {
        String factory = access.require("read");
        require(q != null && Objects.equals(factory, q.getFactoryCode()), "参数依据与实际工厂不一致");
        exact(q.getTaskCode());
        exact(q.getArrangeCode());
        exact(q.getDeviceCode());
        var task =
                one(
                        tasks.selectList(
                                new LambdaQueryWrapper<MesTaskEntity>()
                                        .eq(MesTaskEntity::getFactoryCode, factory)
                                        .eq(MesTaskEntity::getCode, q.getTaskCode())
                                        .last("LIMIT 2")),
                        "工单");
        var arrange =
                one(
                        arrangements.selectList(
                                new LambdaQueryWrapper<MesTaskArrangeEntity>()
                                        .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                                        .eq(MesTaskArrangeEntity::getCode, q.getArrangeCode())
                                        .last("LIMIT 2")),
                        "派工");
        var operation =
                one(
                        operations.selectList(
                                new LambdaQueryWrapper<MesProduceTaskEntity>()
                                        .eq(MesProduceTaskEntity::getFactoryCode, factory)
                                        .eq(
                                                MesProduceTaskEntity::getCode,
                                                arrange.getProduceTaskCode())
                                        .last("LIMIT 2")),
                        "工序");
        Long tenant = TenantContextHolder.getTenantId();
        require(
                tenant != null
                        && Objects.equals(task.getTenantId(), tenant)
                        && Objects.equals(arrange.getTenantId(), tenant)
                        && Objects.equals(operation.getTenantId(), tenant)
                        && Objects.equals(task.getFactoryCode(), factory)
                        && Objects.equals(arrange.getFactoryCode(), factory)
                        && Objects.equals(operation.getFactoryCode(), factory)
                        && Objects.equals(task.getCode(), q.getTaskCode())
                        && Objects.equals(arrange.getCode(), q.getArrangeCode())
                        && Objects.equals(arrange.getTaskCode(), task.getCode())
                        && Objects.equals(operation.getTaskCode(), task.getCode())
                        && Objects.equals(operation.getCode(), arrange.getProduceTaskCode())
                        && Objects.equals(operation.getRouteCode(), arrange.getRouteCode())
                        && Objects.equals(
                                operation.getSourceRouteNodeCode(),
                                arrange.getSourceRouteNodeCode())
                        && Objects.equals(operation.getProcessRouteId(), task.getProcessRouteId())
                        && Objects.equals(
                                operation.getProcessRouteCode(), task.getProcessRouteCode()),
                "工单、派工和原工序/节点身份不一致");
        require(
                task.getStatus() != null
                        && Set.of("ISSUE", "DISPATCH", "PRODUCT").contains(task.getStatus())
                        && arrange.getStatus() != null
                        && Set.of("RECEIVE", "PRODUCT", "PAUSE").contains(arrange.getStatus())
                        && operation.getStatus() != null
                        && Set.of("UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE")
                                .contains(operation.getStatus()),
                "只有已接收且未结束的正式派工可下发参数");
        exact(arrange.getActualWorksiteCode());
        require(
                containsStation(arrange.getWorksiteCodes(), arrange.getActualWorksiteCode())
                        && containsStation(
                                operation.getWorksiteCodes(), arrange.getActualWorksiteCode()),
                "实际工位不在原工序及派工的授权工位中");
        require(
                arrange.getPlannedDeviceCode() == null
                        || arrange.getPlannedDeviceCode().isBlank()
                        || Objects.equals(arrange.getPlannedDeviceCode(), q.getDeviceCode()),
                "实际设备与本次排产选定设备冲突");
        var bindingRows =
                bindings.selectList(
                        new LambdaQueryWrapper<MesWorksiteMoldEntity>()
                                .eq(MesWorksiteMoldEntity::getFactoryCode, factory)
                                .eq(
                                        MesWorksiteMoldEntity::getWorksiteCode,
                                        arrange.getActualWorksiteCode())
                                .eq(MesWorksiteMoldEntity::getDeviceCode, q.getDeviceCode())
                                .last("LIMIT 2"));
        require(
                !bindingRows.isEmpty()
                        && bindingRows.stream()
                                .allMatch(
                                        b ->
                                                Objects.equals(b.getTenantId(), tenant)
                                                        && Objects.equals(
                                                                b.getFactoryCode(), factory)
                                                        && Objects.equals(
                                                                b.getWorksiteCode(),
                                                                arrange.getActualWorksiteCode())
                                                        && Objects.equals(
                                                                b.getDeviceCode(),
                                                                q.getDeviceCode())),
                "设备未与派工已确认的实际工位绑定");
        require(
                task.getEngineeringReleaseId() != null
                        && task.getEngineeringReleaseId() > 0
                        && task.getEngineeringVersionNo() != null
                        && task.getEngineeringVersionNo() > 0
                        && task.getEngineeringBundleSnapshot() != null
                        && Objects.equals(
                                hash(task.getEngineeringBundleSnapshot()),
                                task.getEngineeringContentHash()),
                "工单工程版本原件或指纹不可核验");
        var bundle =
                freeze.validateIssuedSnapshot(
                        task,
                        JSONUtil.toList(
                                task.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class));
        var catalog = bundle.catalog();
        var node = bundle.nodeSnapshots().get(arrange.getSourceRouteNodeCode());
        require(
                Objects.equals(
                        catalog.getRouteNodeProcesses().get(arrange.getSourceRouteNodeCode()),
                        arrange.getRouteCode()),
                "实际工序与原冻结节点的主工序不一致");
        validateEngineeringBasis(task, arrange, bundle);
        require(
                Boolean.TRUE.equals(catalog.getAvailable())
                        && node != null
                        && Objects.equals(
                                catalog.getStandardNo(), task.getProcessParameterStandardNo())
                        && Objects.equals(
                                catalog.getVersionNo(), task.getProcessParameterStandardVersion())
                        && Objects.equals(
                                operation.getProcessParameterStandardSnapshot(), node.snapshot())
                        && Objects.equals(
                                operation.getProcessParameterSnapshotFingerprint(),
                                node.fingerprint())
                        && Objects.equals(
                                operation.getProcessParameterStandardNo(), catalog.getStandardNo())
                        && Objects.equals(
                                operation.getProcessParameterStandardVersion(),
                                catalog.getVersionNo()),
                "原工序参数与完整工单冻结目录不一致");
        var parameters =
                catalog.getParameters().stream()
                        .filter(
                                p ->
                                        Objects.equals(
                                                p.getRouteNodeCode(),
                                                arrange.getSourceRouteNodeCode()))
                        .sorted(Comparator.comparing(p -> p.getIdentifier()))
                        .map(
                                p -> {
                                    exact(p.getIdentifier());
                                    require(
                                            p.getUnit() != null
                                                    && !p.getUnit().isBlank()
                                                    && p.getUnit().length() <= 40,
                                            "下发参数缺少明确单位");
                                    require(
                                            p.getTargetValue() != null
                                                    && (p.getLowerLimit() == null
                                                            || p.getTargetValue()
                                                                            .compareTo(
                                                                                    p
                                                                                            .getLowerLimit())
                                                                    >= 0)
                                                    && (p.getUpperLimit() == null
                                                            || p.getTargetValue()
                                                                            .compareTo(
                                                                                    p
                                                                                            .getUpperLimit())
                                                                    <= 0),
                                            "下发目标缺失或超出冻结上下限");
                                    return new MesParameterDispatchContextDTO.Parameter(
                                            p.getIdentifier(),
                                            p.getUnit(),
                                            decimal(p.getTargetValue()),
                                            decimal(p.getLowerLimit()),
                                            decimal(p.getUpperLimit()));
                                })
                        .toList();
        require(!parameters.isEmpty() && parameters.size() <= 100, "该节点没有可下发参数或超过100项");
        return new MesParameterDispatchContextDTO.Evidence()
                .setSchemaVersion("MES_PARAMETER_DISPATCH@1")
                .setFactoryCode(factory)
                .setTaskCode(task.getCode())
                .setArrangeCode(arrange.getCode())
                .setOperationCode(operation.getCode())
                .setProcessCode(arrange.getRouteCode())
                .setRouteNodeCode(arrange.getSourceRouteNodeCode())
                .setStationCode(arrange.getActualWorksiteCode())
                .setDeviceCode(q.getDeviceCode())
                .setEngineeringReleaseId(task.getEngineeringReleaseId().toString())
                .setEngineeringVersion(task.getEngineeringVersionNo())
                .setEngineeringHash(task.getEngineeringContentHash())
                .setSnapshotFingerprint(task.getProcessParameterSnapshotFingerprint())
                .setNodeSnapshotFingerprint(node.fingerprint())
                .setStandardNo(catalog.getStandardNo())
                .setStandardVersion(catalog.getVersionNo())
                .setReworkMaterialPlanId(
                        task.getReworkMaterialPlanId() == null
                                ? null
                                : task.getReworkMaterialPlanId().toString())
                .setReworkEngineeringMode(task.getReworkEngineeringMode())
                .setReworkEngineeringScopeFingerprint(task.getReworkEngineeringScopeFingerprint())
                .setReworkEngineeringOverrideId(
                        task.getReworkEngineeringOverrideId() == null
                                ? null
                                : task.getReworkEngineeringOverrideId().toString())
                .setReworkEngineeringOverrideHash(task.getReworkEngineeringOverrideHash())
                .setParameters(parameters);
    }

    private <T> T one(List<T> rows, String label) {
        require(rows != null && rows.size() == 1, label + "不存在或不唯一");
        return rows.get(0);
    }

    /** 原包的路线与参数分别核源；下达时追加的执行/SOP字段不应冒充另一工程版本。 */
    private void validateEngineeringBasis(
            MesTaskEntity task,
            MesTaskArrangeEntity arrange,
            MesProcessParameterStandardFreezeService.FrozenBundle actual) {
        var original = engineering.readIssuedOriginal(task);
        var routes = engineering.executionRoutes(original);
        var approved =
                freeze.freezeFromEngineeringSnapshot(
                        task, routes, original.parameterCatalogSnapshot());
        var selected =
                routes.stream()
                        .filter(
                                route ->
                                        Objects.equals(
                                                route.getRouteNodeCode(),
                                                arrange.getSourceRouteNodeCode()))
                        .findFirst();
        require(
                selected.isPresent()
                        && Objects.equals(
                                approved.catalog()
                                        .getRouteNodeProcesses()
                                        .get(arrange.getSourceRouteNodeCode()),
                                arrange.getRouteCode())
                        && selected.get().getMdmProcessProcedureWorkList().stream()
                                .anyMatch(
                                        site ->
                                                Objects.equals(
                                                        site.getWorkCode(),
                                                        arrange.getActualWorksiteCode())),
                "所选节点、主工序或实际工位不属于原工程包");
        if (!"REWORK".equals(task.getResourceType())) {
            require(
                    engineering.sameProjection(actual.snapshot(), approved.snapshot())
                            && Objects.equals(
                                    approved.catalog().getRouteNodeProcesses(),
                                    actual.catalog().getRouteNodeProcesses())
                            && engineering.sameProjection(
                                    task.getBomSnapshot(), original.bomSnapshot()),
                    "下发参数、完整节点或BOM投影与原工程包不一致");
            return;
        }
        // 返工可有正式批准的单节点参数覆盖，不能强行与原生产参数相等。
        // 本地批准方案拥有覆盖依据；这里保留独立审批身份，不冒称原生产包参数。
        require(
                task.getReworkMaterialPlanId() != null
                        && task.getReworkMaterialPlanId() > 0
                        && Set.of(
                                        MesReworkEngineeringOverrideService.INHERIT_SOURCE,
                                        MesReworkEngineeringOverrideService.APPROVED_OVERRIDE)
                                .contains(Objects.toString(task.getReworkEngineeringMode(), ""))
                        && task.getReworkEngineeringScopeSnapshot() != null
                        && Objects.equals(
                                task.getReworkEngineeringScopeFingerprint(),
                                hash(task.getReworkEngineeringScopeSnapshot()))
                        && Objects.equals(
                                task.getReworkRouteNodeCode(), arrange.getSourceRouteNodeCode()),
                "返工缺少本地已批准的原参数执行依据");
        reworkPlans.assertReady(task);
    }

    private boolean containsStation(String csv, String code) {
        return csv != null
                && Arrays.stream(csv.split(",")).map(String::trim).anyMatch(code::equals);
    }

    private void exact(String v) {
        require(
                v != null
                        && !v.isBlank()
                        && v.equals(v.trim())
                        && v.length() <= 100
                        && v.chars().noneMatch(Character::isISOControl),
                "请指定完整的原工单、派工、设备及实际工位编码");
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
