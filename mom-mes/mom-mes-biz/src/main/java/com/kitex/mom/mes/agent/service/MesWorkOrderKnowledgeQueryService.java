package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.agent.dto.MesWorkOrderKnowledgeDTO;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实名工厂内的工单知识权威投影；仅读取MES已保存的原快照，不改变下达或历史数据。
 * 候选count/记录与精确快照均在独立只读RR事务；权限或DB故障不伪装为空正文成功。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('ai:knowledge:view') && @pms.hasPermission('mesTask:show')")
public class MesWorkOrderKnowledgeQueryService {
    private final MesTaskMapper tasks;
    private final RemoteMdmFactoryAccessService factories;
    private final MesWorkOrderKnowledgeSnapshotCodec codec;
    private final ObjectMapper json;

    /** 分页目录的count与身份记录使用同一独立只读快照，不提前加载大JSON。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public MesWorkOrderKnowledgeDTO.CandidatePage candidates(MesWorkOrderKnowledgeDTO.CandidateQuery command) {
        require(command != null, "请提供候选查询");
        Scope scope = scope(command.factoryCode());
        long current = command.current() == null ? 1 : command.current();
        long size = command.size() == null ? 20 : command.size();
        require(current >= 1 && current <= 1_000_000 && size >= 1 && size <= 50
                && (current - 1) * size <= 1_000_000, "工单候选分页超过安全边界");
        String keyword = command.keyword();
        require(keyword == null || keyword.length() <= 100, "工单关键词超过100字");
        LambdaQueryWrapper<MesTaskEntity> query = taskScope(scope)
                .select(MesTaskEntity::getId, MesTaskEntity::getCode, MesTaskEntity::getStatus,
                        MesTaskEntity::getMatCode, MesTaskEntity::getMatName, MesTaskEntity::getBomId,
                        MesTaskEntity::getBomVersion, MesTaskEntity::getProcessRouteId, MesTaskEntity::getProcessRouteCode)
                .orderByDesc(MesTaskEntity::getId);
        if (StringUtils.hasText(keyword)) {
            // MyBatis参数绑定；只查安全身份摘要，不加载原JSON或审批审计字段。
            String search = keyword.trim();
            query.and(part -> part.like(MesTaskEntity::getCode, search)
                    .or().like(MesTaskEntity::getMatCode, search).or().like(MesTaskEntity::getMatName, search));
        }
        Page<MesTaskEntity> page = tasks.selectPage(new Page<>(current, size), query);
        List<MesWorkOrderKnowledgeDTO.WorkOrder> records = page.getRecords().stream().map(this::workOrder).toList();
        return new MesWorkOrderKnowledgeDTO.CandidatePage(current, size, page.getTotal(), records,
                LocalDateTime.now(), List.of("候选仅为工单身份摘要；冻结知识须按精确工单独立核验，不用现行标准补历史"));
    }

    /** 精确工单原JSON与身份头在同一只读快照读取；损坏正文整体关闭，不换查现行标准。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public MesWorkOrderKnowledgeDTO.Evidence evidence(MesWorkOrderKnowledgeDTO.Query command) {
        require(command != null && StringUtils.hasText(command.taskCode()) && command.taskCode().length() <= 100,
                "请选择精确工单号");
        require(command.acceptedSnapshotSchemaVersion() == null
                || "LEGACY_PROCESS_V1".equals(command.acceptedSnapshotSchemaVersion())
                || "ROUTE_NODE_V2".equals(command.acceptedSnapshotSchemaVersion()), "工单知识消费者声明了不支持的冻结协议");
        Scope scope = scope(command.factoryCode());
        String taskCode = command.taskCode().trim();
        List<MesTaskEntity> rows = tasks.selectList(taskScope(scope)
                .eq(MesTaskEntity::getCode, taskCode).orderByAsc(MesTaskEntity::getId).last("LIMIT 2"));
        require(rows.size() == 1, rows.isEmpty() ? "当前租户工厂未找到该工单" : "工单号重复，禁止猜测知识归属");
        MesTaskEntity task = rows.get(0);
        LocalDateTime observed = LocalDateTime.now();
        try {
            MesWorkOrderKnowledgeDTO.WorkOrder header = workOrder(task);
            String base = "MES:WORK_ORDER_FROZEN_KNOWLEDGE:tenant/" + scope.tenant()
                    + "/factory/" + encoded(scope.factory()) + "/task/" + header.taskId();
            MesWorkOrderKnowledgeSnapshotCodec.Bundle bundle = codec.decode(task, base);
            if (nodeProtocol(bundle) && !"ROUTE_NODE_V2".equals(command.acceptedSnapshotSchemaVersion())) {
                return unavailable(scope, taskCode, "UNSUPPORTED_SNAPSHOT_SCHEMA",
                        "该工单使用ROUTE_NODE_V2节点冻结；请升级工单知识消费者并显式声明节点协议能力，禁止按V1工序合同读取", observed);
            }
            MesWorkOrderKnowledgeDTO.Evidence evidence = project(scope, taskCode, header, base, bundle, observed);
            // 多个有界原快照的派生节点仍可能膨胀；不得超过固定HTTP正文边界而伪装完整。
            if (json.writeValueAsBytes(evidence).length > MesWorkOrderKnowledgeSnapshotCodec.MAX_BYTES) {
                return unavailable(scope, taskCode, "OVERSIZED", "工单知识投影超过2MiB，不能返回部分完整图谱", observed);
            }
            return evidence;
        } catch (MesWorkOrderKnowledgeSnapshotCodec.SnapshotFailure invalid) {
            return unavailable(scope, taskCode, invalid.state(), invalid.getMessage(), observed);
        } catch (JsonProcessingException invalidProjection) {
            throw new CheckedException("工单知识投影无法安全序列化");
        }
    }

    /** 权限只取当前实名；工厂头、内部标记或菜单存在都不能替代实际READ绑定。 */
    private Scope scope(String requestedFactory) {
        var user = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(user != null && user.getId() != null && user.getTenantId() != null
                && Objects.equals(user.getTenantId(), tenant) && tenant != null && tenant > 0
                && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && StringUtils.hasText(factory) && factory.length() <= 100 && factory.equals(requestedFactory),
                "工单知识须实名同租户实际工厂读取，禁止跳过隔离或扩范围");
        R<Boolean> check = factories.check(new MdmFactoryAccessCheck().setUserId(user.getId())
                .setFactoryCode(factory).setRequiredPermission("read"));
        require(check != null && check.isOk() && Boolean.TRUE.equals(check.getData()), "当前实名没有该工厂实际READ或授权无法核验");
        return new Scope(tenant, factory);
    }

    private LambdaQueryWrapper<MesTaskEntity> taskScope(Scope scope) {
        return new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getTenantId, scope.tenant())
                .eq(MesTaskEntity::getFactoryCode, scope.factory()).eq(MesTaskEntity::getDelFlag, "0");
    }

    private MesWorkOrderKnowledgeDTO.WorkOrder workOrder(MesTaskEntity task) {
        require(task.getId() != null && task.getId() > 0 && StringUtils.hasText(task.getCode()), "工单身份不完整");
        require(bounded(task.getCode(), 100) && bounded(task.getStatus(), 40)
                && bounded(task.getMatCode(), 100) && bounded(task.getMatName(), 500)
                && bounded(task.getBomVersion(), 100) && bounded(task.getProcessRouteCode(), 100), "工单身份文本超限或含控制字符");
        return new MesWorkOrderKnowledgeDTO.WorkOrder(String.valueOf(task.getId()), task.getCode(), task.getStatus(),
                task.getMatCode(), task.getMatName(), id(task.getBomId()), task.getBomVersion(),
                id(task.getProcessRouteId()), task.getProcessRouteCode());
    }

    private MesWorkOrderKnowledgeDTO.Evidence project(Scope scope, String code, MesWorkOrderKnowledgeDTO.WorkOrder order,
            String base, MesWorkOrderKnowledgeSnapshotCodec.Bundle bundle, LocalDateTime observed) {
        Map<String, MesWorkOrderKnowledgeDTO.Node> nodes = new LinkedHashMap<>();
        List<MesWorkOrderKnowledgeDTO.Relation> relations = new ArrayList<>();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        refs.add(base);
        bundle.snapshots().stream().map(MesWorkOrderKnowledgeDTO.SnapshotProof::evidenceRef).filter(Objects::nonNull).forEach(refs::add);
        node(nodes, base, "WORK_ORDER", code, order.taskId(), code);
        if (StringUtils.hasText(order.materialCode())) {
            String material = base + "/material/" + encoded(order.materialCode());
            node(nodes, material, "MATERIAL", label(order.materialName(), order.materialCode()), null, order.materialCode());
            edge(relations, base, material, "WORK_ORDER_MATERIAL", base);
        }
        String bomRef = reference(bundle, "BOM");
        if (bomRef != null) {
            String bom = base + "/bom/" + order.bomId();
            node(nodes, bom, "BOM_REFERENCE", "BOM " + order.bomId(), order.bomId(), order.bomVersion());
            edge(relations, base, bom, "WORK_ORDER_BOM_REFERENCE", bomRef);
        }
        String routeRef = reference(bundle, "PROCESS_ROUTE");
        String route = base + "/route/" + order.processRouteId();
        node(nodes, route, "PROCESS_ROUTE", order.processRouteCode(), order.processRouteId(), order.processRouteCode());
        edge(relations, base, route, "WORK_ORDER_ROUTE", routeRef);
        for (MesWorkOrderKnowledgeDTO.Process process : bundle.processes()) {
            String processId = processId(base, process.routeNodeCode(), process.processCode());
            node(nodes, processId, "PROCESS", label(process.processName(), process.processCode()), null, process.processCode());
            edge(relations, route, processId, "ROUTE_HAS_PROCESS", routeRef);
            for (MesWorkOrderKnowledgeDTO.SopReference sop : process.sopReferences()) {
                String sopId = base + "/sop/" + sop.referenceHash();
                node(nodes, sopId, "SOP_REFERENCE", sop.name(), sop.sourceId(), null);
                refs.add(sop.evidenceRef());
                edge(relations, processId, sopId, "PROCESS_SOP_REFERENCE", sop.evidenceRef());
            }
        }
        MesWorkOrderKnowledgeDTO.Standard standard = bundle.standard();
        if (standard != null && standard.available()) {
            String standardRef = reference(bundle, "PROCESS_PARAMETER_STANDARD");
            String standardId = base + "/standard/" + standard.standardId() + "/version/" + standard.versionNo();
            node(nodes, standardId, "PROCESS_STANDARD", standard.standardName(), standard.standardId(), standard.standardNo());
            edge(relations, base, standardId, "WORK_ORDER_STANDARD", standardRef);
            for (MesWorkOrderKnowledgeDTO.Parameter parameter : standard.parameters()) {
                String parameterId = base + "/parameter/" + parameter.sourceItemId();
                node(nodes, parameterId, "PROCESS_PARAMETER", parameter.parameterName(), parameter.sourceItemId(), parameter.identifier());
                edge(relations, standardId, parameterId, "STANDARD_CONTROLS_PARAMETER", standardRef);
                edge(relations, processId(base, parameter.routeNodeCode(), parameter.processCode()), parameterId, "PROCESS_HAS_PARAMETER", standardRef);
            }
        }
        return new MesWorkOrderKnowledgeDTO.Evidence(nodeProtocol(bundle) ? 2 : 1, "MES", "WORK_ORDER_FROZEN_KNOWLEDGE", scope.tenant().toString(),
                scope.factory(), code, true, bundle.dataGaps().isEmpty() ? "FROZEN_AVAILABLE" : "FROZEN_PARTIAL", order,
                bundle.snapshots(), bundle.processes(), standard, List.copyOf(nodes.values()), List.copyOf(relations),
                List.copyOf(refs), bundle.dataGaps(), observed);
    }

    private boolean nodeProtocol(MesWorkOrderKnowledgeSnapshotCodec.Bundle bundle) {
        return bundle.standard() != null && "ROUTE_NODE_V2".equals(bundle.standard().snapshotSchemaVersion());
    }

    /** V2以原稳定节点建边；保留processCode作为主数据语义及V1原关系键。 */
    private String processId(String base, String node, String process) {
        return base + (node == null ? "/process/" + encoded(process) : "/route-node/" + encoded(node));
    }

    private MesWorkOrderKnowledgeDTO.Evidence unavailable(Scope scope, String code, String state, String gap, LocalDateTime observed) {
        return new MesWorkOrderKnowledgeDTO.Evidence(1, "MES", "WORK_ORDER_FROZEN_KNOWLEDGE", scope.tenant().toString(),
                scope.factory(), code, false, state, null, List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(gap), observed);
    }

    private String reference(MesWorkOrderKnowledgeSnapshotCodec.Bundle bundle, String kind) {
        return bundle.snapshots().stream().filter(proof -> kind.equals(proof.kind())).findFirst()
                .map(MesWorkOrderKnowledgeDTO.SnapshotProof::evidenceRef).orElse(null);
    }

    private void node(Map<String, MesWorkOrderKnowledgeDTO.Node> nodes, String id, String type, String label, String objectId, String code) {
        nodes.putIfAbsent(id, new MesWorkOrderKnowledgeDTO.Node(id, type, label, objectId, code));
    }

    private void edge(List<MesWorkOrderKnowledgeDTO.Relation> edges, String from, String to, String type, String ref) {
        edges.add(new MesWorkOrderKnowledgeDTO.Relation(from, to, type, ref));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String id(Long value) {
        return value == null ? null : value.toString();
    }

    private static String label(String name, String code) {
        return StringUtils.hasText(name) ? name : code;
    }

    private static boolean bounded(String value, int limit) {
        return value == null || value.length() <= limit && value.chars().noneMatch(c -> c < 32 || c == 127);
    }
    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    /** 只保存当前实名已核验的业务范围，不接受调用者自造租户。 */
    private record Scope(Long tenant, String factory) {
    }
}
