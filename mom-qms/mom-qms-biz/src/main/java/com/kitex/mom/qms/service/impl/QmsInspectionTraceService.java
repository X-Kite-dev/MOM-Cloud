package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import cn.hutool.core.util.StrUtil;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.qms.dto.QmsInspectionTraceRows.*;
import com.kitex.mom.qms.mapper.QmsInspectionTraceMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Actual stored inspection/rework genealogy; never infers original per-piece qualification. */
@Service
@RequiredArgsConstructor
public class QmsInspectionTraceService {
    private final QmsInspectionTraceMapper mapper;
    private final QmsInspectionHistorySupport access;
    private final PermissionService permissions;

    public record Node(
            Long id,
            int round,
            String orderNo,
            String sourceTaskCode,
            String matCode,
            String matName,
            String processCode,
            String processName,
            String batchNo,
            Long standardId,
            String standardName,
            Integer num,
            Integer inspectionNum,
            String status,
            String result) {}

    public record Rework(
            Long id,
            String requestId,
            Long handleRecordId,
            Long parentTaskId,
            Long childTaskId,
            int quantity,
            String status,
            String mesTaskCode,
            String reinspectionStatus,
            String reason) {}

    public record View(
            String inspectionType,
            Long taskId,
            Long tenantId,
            String factoryCode,
            Long rootTaskId,
            String originQualification,
            boolean firstPassEligible,
            List<Node> nodes,
            List<Rework> reworks) {
        public View {
            nodes = List.copyOf(nodes);
            reworks = List.copyOf(reworks);
        }
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW)
    public View view(String type, Long taskId) {
        return inCurrentTransaction(type, taskId);
    }

    /** 领域协作沿用调用方事务，复用完整的来源、分支数量与成环校验。 */
    View inCurrentTransaction(String type, Long taskId) {
        require("PROCEDURE".equals(type) || "PRODUCT".equals(type), "不支持的检验类型");
        if (!permissions.hasPermission(
                ("PRODUCT".equals(type) ? "productTask" : "procedureTask") + ":show"))
            throw new AccessDeniedException("没有查看该类检验任务的权限");
        access.requireRead();
        require(primary(taskId), "请指定有效的检验任务");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var tasks = new LinkedHashMap<Long, Task>();
        var orders = new LinkedHashMap<Long, Order>();
        Set<Long> frontier = Set.of(taskId);
        while (!frontier.isEmpty()) {
            require(tasks.size() + frontier.size() <= 128, "关联检验超过128个，请先治理历史");
            var actual =
                    "PRODUCT".equals(type)
                            ? mapper.productTasks(tenant, factory, List.copyOf(frontier))
                            : mapper.procedureTasks(tenant, factory, List.copyOf(frontier));
            require(actual != null && actual.size() == frontier.size(), "检验流转有缺失、已删除或异组织任务");
            var seen = new HashSet<Long>();
            for (var row : actual) {
                require(
                        row != null
                                && frontier.contains(row.getId())
                                && seen.add(row.getId())
                                && Objects.equals(tenant, row.getTenantId())
                                && Objects.equals(factory, row.getFactoryCode()),
                        "检验流转任务身份不一致");
                tasks.put(row.getId(), row);
            }
            var links = mapper.orders(tenant, factory, type, List.copyOf(frontier));
            require(links != null && links.size() <= 128, "关联返工超过128笔或不可读取，请先治理历史");
            var next = new LinkedHashSet<Long>();
            for (var row : links) {
                require(
                        row != null
                                && "0".equals(row.getDelFlag())
                                && primary(row.getId())
                                && primary(row.getInspectionTaskId())
                                && (row.getReinspectionTaskId() == null
                                        || primary(row.getReinspectionTaskId()))
                                && Objects.equals(tenant, row.getTenantId())
                                && Objects.equals(factory, row.getFactoryCode())
                                && Objects.equals(type, row.getInspectionType())
                                && (frontier.contains(row.getInspectionTaskId())
                                        || (row.getReinspectionTaskId() != null
                                                && frontier.contains(row.getReinspectionTaskId()))),
                        "返工关联已删除，或身份及组织不一致");
                var previous = orders.putIfAbsent(row.getId(), row);
                require(previous == null || previous.equals(row), "返工流转读取到不一致的状态");
                require(orders.size() <= 128, "关联返工超过128笔，请先治理历史");
                next.add(row.getInspectionTaskId());
                if (row.getReinspectionTaskId() != null) next.add(row.getReinspectionTaskId());
            }
            next.removeAll(tasks.keySet());
            frontier = next;
        }
        var handles = new HashMap<Long, Handle>();
        if (!orders.isEmpty()) {
            var ids = orders.values().stream().map(Order::getHandleRecordId).toList();
            require(
                    ids.stream().allMatch(QmsInspectionTraceService::primary)
                            && new HashSet<>(ids).size() == ids.size(),
                    "返工处置来源缺失或重复");
            var rows =
                    "PRODUCT".equals(type)
                            ? mapper.productHandles(tenant, factory, ids)
                            : mapper.procedureHandles(tenant, factory, ids);
            require(rows != null && rows.size() == ids.size(), "返工处置记录缺失、已删除或异组织");
            for (var row : rows) {
                require(
                        row != null
                                && ids.contains(row.getId())
                                && Objects.equals(tenant, row.getTenantId())
                                && Objects.equals(factory, row.getFactoryCode())
                                && handles.put(row.getId(), row) == null,
                        "返工处置身份不一致");
            }
        }
        return assemble(type, taskId, tenant, factory, tasks, orders, handles);
    }

    private View assemble(
            String type,
            Long selected,
            Long tenant,
            String factory,
            Map<Long, Task> tasks,
            Map<Long, Order> orders,
            Map<Long, Handle> handles) {
        var incoming = new HashMap<Long, Order>();
        var outgoing = new HashMap<Long, List<Order>>();
        var requests = new HashSet<String>();
        for (var row : orders.values()) {
            require(
                    StrUtil.isNotBlank(row.getRequestId())
                            && row.getRequestId().length() <= 200
                            && requests.add(row.getRequestId()),
                    "返工永久请求缺失或重复");
            var parent = tasks.get(row.getInspectionTaskId());
            var handle = handles.get(row.getHandleRecordId());
            int quantity = quantity(row);
            require(
                    parent != null
                            && positive(parent.getNum())
                            && quantity <= parent.getNum()
                            && handle != null
                            && Objects.equals(handle.getTaskId(), parent.getId())
                            && "REWORK".equals(handle.getHandleResult())
                            && Objects.equals(handle.getHandleNum(), quantity),
                    "返工量或实际处置来源不一致");
            require(
                    Objects.equals(row.getOriginalMesTaskCode(), parent.getOrderNo())
                            && StrUtil.isNotBlank(parent.getOrderNo())
                            && Objects.equals(
                                    row.getOriginalMesProduceTaskCode(), parent.getSourceTaskCode())
                            && Objects.equals(row.getReworkProcessCode(), parent.getProcessCode())
                            && Objects.equals(row.getMatCode(), parent.getMatCode())
                            && Objects.equals(row.getMatName(), parent.getMatName())
                            && Objects.equals(row.getMatSpec(), parent.getMatSpec())
                            && Objects.equals(row.getBatchNo(), parent.getBatchNo())
                            && Objects.equals(
                                    row.getReason(),
                                    StrUtil.blankToDefault(
                                            StrUtil.trim(handle.getHandleRemark()), "质量不合格返工")),
                    "返工冻结来源与原检验或处置不一致");
            if (row.getReinspectionTaskId() == null) {
                require(
                        Set.of("DISPATCHING", "DISPATCH_FAILED", "MES_CREATED")
                                        .contains(Objects.toString(row.getStatus(), ""))
                                && row.getMesCompletedQuantity() == null
                                && ("MES_CREATED".equals(row.getStatus())
                                        ? StrUtil.isNotBlank(row.getMesTaskCode())
                                        : StrUtil.isBlank(row.getMesTaskCode())),
                        "待复检返工阶段与实际事实不一致");
            } else {
                var child = tasks.get(row.getReinspectionTaskId());
                require(
                        incoming.putIfAbsent(row.getReinspectionTaskId(), row) == null,
                        "同一复检有多个返工来源");
                require(
                        child != null
                                && Set.of(
                                                "REINSPECTION_PENDING",
                                                "RESULT_SYNC_PENDING",
                                                "RESULT_SYNC_FAILED",
                                                "CLOSED")
                                        .contains(Objects.toString(row.getStatus(), ""))
                                && row.getMesCompletedQuantity() != null
                                && row.getMesCompletedQuantity().compareTo(row.getQuantity()) == 0
                                && StrUtil.isNotBlank(row.getMesTaskCode())
                                && Objects.equals(
                                        child.getSourceRequestId(),
                                        "REWORK-REINSPECTION:" + row.getRequestId())
                                && StrUtil.isNotBlank(child.getSourceTaskCode())
                                && Objects.equals(child.getOrderNo(), row.getMesTaskCode())
                                && Objects.equals(child.getNum(), quantity)
                                && positive(child.getInspectionNum())
                                && child.getInspectionNum() <= quantity
                                && Objects.equals(child.getMatCode(), row.getMatCode())
                                && Objects.equals(child.getMatName(), row.getMatName())
                                && Objects.equals(child.getMatSpec(), row.getMatSpec())
                                && Objects.equals(
                                        child.getProcessCode(), row.getReworkProcessCode())
                                && Objects.equals(child.getBatchNo(), row.getBatchNo())
                                && Objects.equals(child.getStandardId(), parent.getStandardId())
                                && Objects.equals(child.getDeptCode(), parent.getDeptCode()),
                        "复检实际来源、数量或返工阶段不一致");
            }
            outgoing.computeIfAbsent(parent.getId(), ignored -> new ArrayList<>()).add(row);
        }
        var roots = tasks.values().stream().filter(t -> !incoming.containsKey(t.getId())).toList();
        require(roots.size() == 1, "检验流转有环或来源不唯一");
        var root = roots.get(0);
        require(
                root.getSourceRequestId() == null
                        || !root.getSourceRequestId().startsWith("REWORK-REINSPECTION:"),
                "复检缺少原返工关联，不能冒充来源检验");
        var rounds = new HashMap<Long, Integer>();
        var queue = new ArrayDeque<Long>();
        rounds.put(root.getId(), 0);
        queue.add(root.getId());
        while (!queue.isEmpty()) {
            Long id = queue.remove();
            long total = 0;
            for (var row : outgoing.getOrDefault(id, List.of())) {
                total += quantity(row);
                require(total <= tasks.get(id).getNum(), "多笔返工数量超过来源检验批量");
                if (row.getReinspectionTaskId() != null) {
                    require(
                            rounds.putIfAbsent(row.getReinspectionTaskId(), rounds.get(id) + 1)
                                    == null,
                            "检验流转关系成环或重复");
                    queue.add(row.getReinspectionTaskId());
                }
            }
        }
        require(rounds.size() == tasks.size(), "检验流转存在孤立或成环节点");
        var nodes =
                tasks.values().stream()
                        .map(
                                t ->
                                        new Node(
                                                t.getId(),
                                                rounds.get(t.getId()),
                                                t.getOrderNo(),
                                                t.getSourceTaskCode(),
                                                t.getMatCode(),
                                                t.getMatName(),
                                                t.getProcessCode(),
                                                t.getProcessName(),
                                                t.getBatchNo(),
                                                t.getStandardId(),
                                                t.getStandardName(),
                                                t.getNum(),
                                                t.getInspectionNum(),
                                                t.getStatus(),
                                                t.getResult()))
                        .sorted(Comparator.comparingInt(Node::round).thenComparing(Node::id))
                        .toList();
        var edges =
                orders.values().stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (Order r) -> rounds.get(r.getInspectionTaskId()))
                                        .thenComparing(Order::getId))
                        .map(
                                r ->
                                        new Rework(
                                                r.getId(),
                                                r.getRequestId(),
                                                r.getHandleRecordId(),
                                                r.getInspectionTaskId(),
                                                r.getReinspectionTaskId(),
                                                quantity(r),
                                                r.getStatus(),
                                                r.getMesTaskCode(),
                                                r.getReinspectionStatus(),
                                                r.getReason()))
                        .toList();
        return new View(
                type, selected, tenant, factory, root.getId(), "UNVERIFIED", false, nodes, edges);
    }

    private static boolean primary(Long id) {
        return id != null && id > 0;
    }

    private static boolean positive(Integer number) {
        return number != null && number > 0;
    }

    private static int quantity(Order row) {
        require(row.getQuantity() != null && row.getQuantity().signum() > 0, "返工数量缺失或无效");
        try {
            return row.getQuantity().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new com.kitex.common.core.exception.CheckedException("返工数量不是可支持的正整数", invalid);
        }
    }
}
