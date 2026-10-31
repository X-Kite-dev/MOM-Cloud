package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmProcessParameterStandardDTO;
import com.kitex.mom.mdm.feign.RemoteMdmProcessParameterStandardService;
import com.kitex.mom.mes.dto.MdmProcessRouteWorkingDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 在MES工单下达边界冻结MDM已发布工艺参数标准。
 *
 * <p>MDM不可用和“没有发布标准”是两种不同结果：前者阻断下达，后者冻结明确的空目录，
 * 使历史工单后续查询不会错误套用新发布标准。</p>
 */
@Service
@RequiredArgsConstructor
public class MesProcessParameterStandardFreezeService {

    private final RemoteMdmProcessParameterStandardService remoteStandardService;

    public FrozenBundle freeze(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes) {
        if (task == null || task.getProcessRouteId() == null || StrUtil.isBlank(task.getProcessRouteCode())
                || StrUtil.isBlank(task.getFactoryCode()) || routes == null || routes.isEmpty()) {
            throw new CheckedException("冻结工艺参数标准缺少工单、路线、工厂或工序信息");
        }
        // 新下达必须使用原路线已经保存的真实身份；缺节点的旧草稿须重新选择路线。
        // 不在这里查询当前MDM后补旧快照，避免把已删除/重加节点误认作原节点。
        Map<String, String> nodeProcesses = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingDTO route : routes) {
            String process = route == null ? null
                    : StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode());
            String node = route == null ? null : route.getRouteNodeCode();
            if (node == null || !node.matches("RN-[1-9][0-9]{0,18}") || StrUtil.isBlank(process)
                    || nodeProcesses.putIfAbsent(node, process) != null
                    || (StrUtil.isNotBlank(route.getFactoryCode())
                    && !Objects.equals(task.getFactoryCode(), route.getFactoryCode()))) {
                throw new CheckedException("工单路线缺少有效唯一节点或工厂不一致，请重新选择真实工艺路线后下达");
            }
        }
        R<MdmProcessParameterStandardDTO.FrozenCatalog> response = remoteStandardService.resolveNodes(
                new MdmProcessParameterStandardDTO.ResolveQuery()
                        .setFactoryCode(task.getFactoryCode())
                        .setProcessRouteId(task.getProcessRouteId())
                        .setRouteCode(task.getProcessRouteCode())
                        .setProcessCodes(nodeProcesses.values().stream().distinct().sorted().toList())
                        .setRouteNodeCodes(nodeProcesses.keySet().stream().sorted().toList())
                        .setEffectiveTime(LocalDateTime.now()));
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("MDM未返回可冻结的工艺参数标准目录，禁止下达生产工单");
        }
        return validateCatalog(task, nodeProcesses, response.getData(), true);
    }

    /**
     * 工程联合版本已封存参数目录，MES直接消费该原版本，
     * 不再按下达时间选择“今日发布版”。
     */
    public FrozenBundle freezeFromEngineeringSnapshot(MesTaskEntity task,
                                                      List<MdmProcessRouteWorkingDTO> routes,
                                                      String snapshot) {
        if (task == null || task.getProcessRouteId() == null || routes == null || routes.isEmpty()
                || StrUtil.isBlank(snapshot)) {
            throw new CheckedException("工程版本参数冻结缺少工单、路线或原目录");
        }
        Map<String, String> nodeProcesses = nodeProcesses(task, routes);
        MdmProcessParameterStandardDTO.FrozenCatalog catalog;
        try {
            catalog = JSONUtil.toBean(snapshot, MdmProcessParameterStandardDTO.FrozenCatalog.class);
        } catch (Exception ex) {
            throw new CheckedException("工程版本参数目录JSON损坏");
        }
        return validateCatalog(task, nodeProcesses, catalog, false);
    }

    /**
     * 从原生产工单已经批准并冻结的完整参数目录裁出返工节点。
     * 不访问今日MDM目录，也不把同主工序的另一稳定节点参数误用于本次返工。
     */
    public FrozenBundle inheritReworkNode(MesTaskEntity source, MesProduceTaskEntity sourceOperation,
                                          MesTaskEntity rework, MdmProcessRouteWorkingDTO route) {
        if (source == null || sourceOperation == null || rework == null || route == null
                || StrUtil.isBlank(source.getProcessParameterStandardSnapshot())
                || StrUtil.isBlank(source.getProcessParameterSnapshotFingerprint())) {
            throw new CheckedException("原生产工单或原实际工序缺少已冻结参数标准，不能批准节点返工执行依据");
        }
        if (!Objects.equals(source.getProcessParameterSnapshotFingerprint(),
                sha256(source.getProcessParameterStandardSnapshot()))) {
            throw new CheckedException("原生产工单参数标准正文与指纹不一致");
        }
        MdmProcessParameterStandardDTO.FrozenCatalog catalog;
        List<MdmProcessRouteWorkingDTO> sourceRoutes;
        try {
            catalog = JSONUtil.toBean(source.getProcessParameterStandardSnapshot(),
                    MdmProcessParameterStandardDTO.FrozenCatalog.class);
            sourceRoutes = JSONUtil.toList(source.getProcessRouteSnapshot(), MdmProcessRouteWorkingDTO.class);
        } catch (Exception exception) {
            throw new CheckedException("原生产工单参数或路线冻结正文损坏");
        }
        validateCatalog(source, nodeProcesses(source, sourceRoutes), catalog, false);
        String node = route.getRouteNodeCode();
        String process = StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode());
        if (StrUtil.isBlank(node) || !Objects.equals(catalog.getRouteNodeProcesses().get(node), process)) {
            throw new CheckedException("原冻结参数目录未唯一覆盖返工来源节点及主工序");
        }
        ProcessSnapshot subset = processSnapshot(catalog, node);
        MdmProcessParameterStandardDTO.FrozenCatalog nodeCatalog = JSONUtil.toBean(subset.snapshot(),
                MdmProcessParameterStandardDTO.FrozenCatalog.class);
        FrozenBundle inherited = validateCatalog(rework, Map.of(node, process), nodeCatalog, false);
        if (!Objects.equals(sourceOperation.getTenantId(), source.getTenantId())
                || !Objects.equals(sourceOperation.getFactoryCode(), source.getFactoryCode())
                || !Objects.equals(sourceOperation.getTaskCode(), source.getCode())
                || !Objects.equals(sourceOperation.getProcessRouteId(), source.getProcessRouteId())
                || !Objects.equals(sourceOperation.getProcessRouteCode(), source.getProcessRouteCode())
                || !Objects.equals(sourceOperation.getSourceRouteNodeCode(), node)
                || !Objects.equals(sourceOperation.getRouteCode(), process)
                || !Objects.equals(sourceOperation.getProcessParameterStandardNo(), inherited.catalog().getStandardNo())
                || !Objects.equals(sourceOperation.getProcessParameterStandardVersion(), inherited.catalog().getVersionNo())
                || !Objects.equals(sourceOperation.getProcessParameterStandardSnapshot(), inherited.snapshot())
                || !Objects.equals(sourceOperation.getProcessParameterSnapshotFingerprint(), inherited.fingerprint())) {
            throw new CheckedException("原实际工序的节点参数快照与原工单批准目录不一致，不能猜测继承");
        }
        return inherited;
    }

    /**
     * 下达时只复核返工方案已批准的节点参数正文，不重新选择当前标准。
     */
    public FrozenBundle validateReworkSnapshot(MesTaskEntity task,
                                               List<MdmProcessRouteWorkingDTO> routes) {
        return validateIssuedSnapshot(task, routes);
    }

    /** 复核已下达工单的原冻结目录；生产与返工共用，不重选今日MDM。 */
    public FrozenBundle validateIssuedSnapshot(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes) {
        if (task == null || StrUtil.isBlank(task.getProcessParameterStandardSnapshot())
                || StrUtil.isBlank(task.getProcessParameterSnapshotFingerprint())
                || !Objects.equals(task.getProcessParameterSnapshotFingerprint(),
                sha256(task.getProcessParameterStandardSnapshot()))) {
            throw new CheckedException("工单参数标准批准正文缺失或指纹不一致");
        }
        MdmProcessParameterStandardDTO.FrozenCatalog catalog;
        try {
            catalog = JSONUtil.toBean(task.getProcessParameterStandardSnapshot(),
                    MdmProcessParameterStandardDTO.FrozenCatalog.class);
        } catch (Exception exception) {
            throw new CheckedException("工单参数标准批准正文损坏");
        }
        return validateCatalog(task, nodeProcesses(task, routes), catalog, false);
    }

    private Map<String, String> nodeProcesses(MesTaskEntity task, List<MdmProcessRouteWorkingDTO> routes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingDTO route : routes) {
            String process = route == null ? null : StrUtil.blankToDefault(route.getProcessCode(), route.getRouteCode());
            String node = route == null ? null : route.getRouteNodeCode();
            if (node == null || !node.matches("RN-[1-9][0-9]{0,18}") || StrUtil.isBlank(process)
                    || result.putIfAbsent(node, process) != null || StrUtil.isNotBlank(route.getFactoryCode())
                    && !Objects.equals(task.getFactoryCode(), route.getFactoryCode())) {
                throw new CheckedException("工单路线缺少有效唯一节点或工厂不一致");
            }
        }
        return result;
    }

    private FrozenBundle validateCatalog(MesTaskEntity task, Map<String, String> nodeProcesses,
                                         MdmProcessParameterStandardDTO.FrozenCatalog catalog,
                                         boolean requireResolutionTime) {
        if (catalog == null) throw new CheckedException("工艺参数标准目录为空");
        if (!MdmProcessParameterStandardDTO.ROUTE_NODE_V2.equals(catalog.getSnapshotSchemaVersion())
                || !Objects.equals(catalog.getProcessRouteId(), task.getProcessRouteId())
                || !Objects.equals(catalog.getRouteCode(), task.getProcessRouteCode())) {
            throw new CheckedException("MDM返回的工艺参数标准协议或生产工单路线不一致");
        }
        // 即使无发布标准/某节点没有参数，也必须用MDM真实全节点映射核实原图归属。
        if (!nodeProcesses.equals(catalog.getRouteNodeProcesses())) {
            throw new CheckedException("MDM真实路线节点与工单原路线的节点/主工序映射不一致");
        }
        if (catalog.getParameters() == null || requireResolutionTime && catalog.getResolvedAt() == null) {
            throw new CheckedException("MDM节点冻结缺少明确参数清单或解析时间");
        }
        List<MdmProcessParameterStandardDTO.FrozenParameter> parameters = catalog.getParameters();
        Set<String> parameterKeys = new HashSet<>();
        Set<Long> itemIds = new HashSet<>();
        if (parameters.stream().anyMatch(item -> item == null
                || !nodeProcesses.containsKey(item.getRouteNodeCode())
                || !Objects.equals(nodeProcesses.get(item.getRouteNodeCode()), item.getProcessCode())
                || StrUtil.isBlank(item.getIdentifier()) || item.getSourceItemId() == null || item.getSourceItemId() <= 0
                || !itemIds.add(item.getSourceItemId())
                || !parameterKeys.add(item.getRouteNodeCode() + "\u0000" + item.getIdentifier()))) {
            throw new CheckedException("MDM返回的参数存在节点/主工序错配、重复身份或空参数标识符");
        }
        if (catalog.getAvailable() == null || (!catalog.getAvailable() && !parameters.isEmpty())) {
            throw new CheckedException("MDM参数标准缺少明确可用状态或无标准却返回参数正文");
        }
        if (Boolean.TRUE.equals(catalog.getAvailable())
                && (catalog.getStandardId() == null || catalog.getStandardId() <= 0 || StrUtil.isBlank(catalog.getStandardNo())
                || catalog.getVersionNo() == null || catalog.getVersionNo() <= 0
                || StrUtil.isBlank(catalog.getContentFingerprint()) || !catalog.getContentFingerprint().matches("[0-9a-f]{64}")
                || catalog.getContentSchemaVersion() == null
                || !Set.of(MdmProcessParameterStandardDTO.LEGACY_PROCESS_V1,
                MdmProcessParameterStandardDTO.NODE_V2).contains(catalog.getContentSchemaVersion()))) {
            throw new CheckedException("MDM已发布工艺参数标准缺少身份、版本、内容协议或指纹");
        }
        if (!catalog.getAvailable() && (catalog.getStandardId() != null || catalog.getStandardNo() != null
                || catalog.getVersionNo() != null || catalog.getContentFingerprint() != null)) {
            throw new CheckedException("MDM未提供可用标准却保留正式标准身份，禁止冻结混合结果");
        }
        String fullSnapshot = JSONUtil.toJsonStr(catalog);
        String fullFingerprint = sha256(fullSnapshot);
        Map<String, ProcessSnapshot> byNode = new LinkedHashMap<>();
        nodeProcesses.keySet().forEach(node -> byNode.put(node, processSnapshot(catalog, node)));
        return new FrozenBundle(catalog, fullSnapshot, fullFingerprint, byNode);
    }

    private ProcessSnapshot processSnapshot(MdmProcessParameterStandardDTO.FrozenCatalog source,
                                            String routeNodeCode) {
        MdmProcessParameterStandardDTO.FrozenCatalog snapshot = new MdmProcessParameterStandardDTO.FrozenCatalog()
                .setAvailable(source.getAvailable())
                .setSnapshotSchemaVersion(source.getSnapshotSchemaVersion())
                .setContentSchemaVersion(source.getContentSchemaVersion())
                .setRouteNodeProcesses(Map.of(routeNodeCode, source.getRouteNodeProcesses().get(routeNodeCode)))
                .setStandardId(source.getStandardId())
                .setStandardNo(source.getStandardNo())
                .setStandardName(source.getStandardName())
                .setVersionNo(source.getVersionNo())
                .setProcessRouteId(source.getProcessRouteId())
                .setRouteCode(source.getRouteCode())
                .setRouteName(source.getRouteName())
                .setEffectiveTime(source.getEffectiveTime())
                .setReleasedTime(source.getReleasedTime())
                .setContentFingerprint(source.getContentFingerprint())
                .setResolvedAt(source.getResolvedAt())
                .setParameters(source.getParameters() == null ? List.of() : source.getParameters().stream()
                        .filter(item -> Objects.equals(routeNodeCode, item.getRouteNodeCode())).toList())
                // 缺口是来源事实，不能通过工序文本包含判断而遗失全局或节点缺口。
                .setDataGaps(source.getDataGaps() == null ? List.of() : source.getDataGaps());
        String json = JSONUtil.toJsonStr(snapshot);
        return new ProcessSnapshot(json, sha256(json));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成MES工艺参数冻结指纹", exception);
        }
    }

    public record FrozenBundle(MdmProcessParameterStandardDTO.FrozenCatalog catalog,
                               String snapshot, String fingerprint,
                               Map<String, ProcessSnapshot> nodeSnapshots) {
    }

    public record ProcessSnapshot(String snapshot, String fingerprint) {
    }
}
