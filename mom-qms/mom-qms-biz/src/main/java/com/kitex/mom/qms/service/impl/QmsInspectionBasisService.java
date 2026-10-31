package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.QmsInspectionBasisMapper;
import com.kitex.mom.qms.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 检验依据的追加式存储；调用方持有实际父任务锁，和原业务事务一起提交。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionBasisService {
    private final QmsInspectionBasisMapper mapper;
    private final QmsInspectionPlanFactory plans;
    private final QmsInspectionHistorySupport access;
    private final PermissionService permissions;
    private final QmsInspectionProductionBasisService production;
    private static final int MAX_PAYLOAD = 2 * 1024 * 1024;

    public record Task(
            String type,
            Long id,
            Long tenantId,
            String factoryCode,
            String sourceTaskCode,
            String sourceRequestId,
            String matCode,
            String processCode,
            String labelType,
            String batchNo,
            Long standardId,
            String standardName,
            Integer num,
            Integer inspectionNum,
            String orderNo,
            String orderId,
            String deptCode) {}

    public record Binding(
            Long id,
            Long standardItemId,
            String name,
            String demand,
            String testMethod,
            String position,
            String checkType,
            String checkClassify,
            Double upperLimit,
            Double lowerLimit,
            Integer totalNum,
            Integer ac,
            Integer re) {}

    public record Snapshot(
            int schemaVersion,
            Task task,
            int revision,
            String operation,
            String previousFingerprint,
            Long createdBy,
            long occurredAt,
            String creationRequest,
            String creationFingerprint,
            QmsFrozenInspectionPlan plan,
            List<Binding> bindings,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO.Evidence
                            productionContext) {
        public Snapshot {
            bindings = bindings == null ? null : List.copyOf(bindings);
        }
    }

    public record View(
            String availability,
            String inspectionType,
            Long taskId,
            Long tenantId,
            String factoryCode,
            Snapshot current,
            Snapshot original) {}

    public QmsFrozenInspectionPlan prepare(
            String type, QmsQualityStandardEntity standard, int batch, Integer requested) {
        access.requireScope();
        ready();
        return plans.prepare(type, standard, batch, requested);
    }

    public boolean sameCreation(String type, Long taskId, String command) {
        var chain = chain(type, taskId);
        if (chain.rows().isEmpty()) return false;
        var original = chain.rows().get(0).snapshot();
        if (!"CREATE".equals(original.operation())) return false;
        require(
                command != null
                        && command.equals(original.creationRequest())
                        && QmsInspectionBasisJson.sha(command)
                                .equals(original.creationFingerprint()),
                "来源请求号与原始报检命令不一致，请核对原请求");
        return true;
    }

    public QmsFrozenInspectionPlan validateCurrent(
            QmsInspectionProductTaskEntity task,
            List<QmsInspectionProductTaskDetailEntity> details) {
        return validate(task(task), product(task, details));
    }

    public QmsFrozenInspectionPlan validateCurrent(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> details) {
        return validate(task(task), procedure(task, details));
    }

    /** 调用方持有实际父任务锁，且已校验当前任务/项目与此链一致。 */
    String currentFingerprint(String type, Long taskId) {
        var rows = chain(type, taskId).rows();
        require(!rows.isEmpty(), "逐件检验缺少冻结依据");
        return rows.get(rows.size() - 1).row().getPayloadFingerprint();
    }

    private QmsFrozenInspectionPlan validate(Task task, List<Binding> actual) {
        var chain = chain(task.type(), task.id());
        if (chain.rows().isEmpty()) return null;
        var snapshot = chain.rows().get(chain.rows().size() - 1).snapshot();
        require(snapshot.task().equals(task), "实际检验任务与冻结依据不一致，请核对原记录");
        require(snapshot.bindings().equals(ordered(actual)), "实际项目与冻结判定或应检量不一致，请核对原记录");
        return snapshot.plan();
    }

    public void record(
            QmsInspectionProductTaskEntity task,
            List<QmsInspectionProductTaskDetailEntity> details,
            QmsFrozenInspectionPlan plan,
            String operation,
            String creationRequest) {
        persist(task(task), product(task, details), plan, operation, creationRequest, null);
    }

    public void record(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> details,
            QmsFrozenInspectionPlan plan,
            String operation,
            String creationRequest) {
        persist(task(task), procedure(task, details), plan, operation, creationRequest, null);
    }

    public com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO.Evidence freezeSource(
            com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam source) {
        access.requireScope();
        return production.freeze(source);
    }

    public void record(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> details,
            QmsFrozenInspectionPlan plan,
            String operation,
            String creationRequest,
            com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO.Evidence context) {
        persist(task(task), procedure(task, details), plan, operation, creationRequest, context);
    }

    private void persist(
            Task task,
            List<Binding> bindings,
            QmsFrozenInspectionPlan plan,
            String operation,
            String command,
            com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO.Evidence context) {
        scope(task.type(), task.id());
        require(
                Objects.equals(task.tenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                task.factoryCode(), FactoryContextHolder.getFactoryCode()),
                "检验依据与实际组织不一致");
        Long actualParent =
                "PRODUCT".equals(task.type())
                        ? mapper.productParent(task.id(), task.tenantId(), task.factoryCode())
                        : mapper.procedureParent(task.id(), task.tenantId(), task.factoryCode());
        require(Objects.equals(actualParent, task.id()), "检验依据父任务缺失或跨组织");
        require(TransactionSynchronizationManager.isActualTransactionActive(), "检验依据必须与原任务在同一事务提交");
        require(Set.of("CREATE", "RECONFIGURE", "APPEND").contains(operation), "检验依据动作无效");
        var chain = chain(task.type(), task.id());
        var previous =
                chain.rows().isEmpty() ? null : chain.rows().get(chain.rows().size() - 1).row();
        int revision = previous == null ? 1 : previous.getRevision() + 1;
        require(
                revision <= 5000
                        && (!"CREATE".equals(operation) || revision == 1)
                        && (!"APPEND".equals(operation) || previous != null),
                "检验依据版本无效或超过5000版");
        require(
                ("CREATE".equals(operation) && command != null)
                        || (!"CREATE".equals(operation) && command == null),
                "检验原始命令只能在首次创建时保存");
        bindings = ordered(bindings);
        validatePlan(task, plan, bindings);
        var user = SecurityUtils.getUser();
        Long actor = user == null ? null : user.getId();
        require(
                user == null || Objects.equals(user.getTenantId(), task.tenantId()),
                "检验依据审计人员与实际租户不一致");
        long now = System.currentTimeMillis();
        String previousHash = previous == null ? null : previous.getPayloadFingerprint();
        if (!"CREATE".equals(operation)) {
            require(context == null, "后续版本不能替换原生产依据");
            context =
                    chain.rows().isEmpty()
                            ? null
                            : chain.rows().get(0).snapshot().productionContext();
        }
        if (context != null) QmsInspectionProductionBasisService.matches(context, task);
        var snapshot =
                new Snapshot(
                        1,
                        task,
                        revision,
                        operation,
                        previousHash,
                        actor,
                        now,
                        command,
                        command == null ? null : QmsInspectionBasisJson.sha(command),
                        plan,
                        bindings,
                        context);
        String payload = QmsInspectionBasisJson.write(snapshot);
        require(
                payload.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD,
                "单版检验依据超过2MiB，请先治理");
        require(
                chain.bytes() + payload.getBytes(StandardCharsets.UTF_8).length <= 8388608,
                "单任务检验依据累计超过8MiB，请先治理");
        var row =
                new QmsInspectionBasisEntity()
                        .setTenantId(task.tenantId())
                        .setFactoryCode(task.factoryCode())
                        .setInspectionType(task.type())
                        .setTaskId(task.id())
                        .setRevision(revision)
                        .setOperation(operation)
                        .setPreviousFingerprint(previousHash)
                        .setPayload(payload)
                        .setPayloadFingerprint(QmsInspectionBasisJson.sha(payload))
                        .setCreatedBy(actor)
                        .setOccurredAt(new Date(now));
        require(mapper.insert(row) == 1 && row.getId() != null && row.getId() > 0, "检验依据原件保存失败");
    }

    public View view(String type, Long taskId) {
        type(type);
        if (!permissions.hasPermission(
                ("PRODUCT".equals(type) ? "productTask" : "procedureTask") + ":show"))
            throw new AccessDeniedException("没有查看该类检验任务的权限");
        access.requireRead();
        scope(type, taskId);
        Long parent =
                "PRODUCT".equals(type)
                        ? mapper.productParent(
                                taskId,
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode())
                        : mapper.procedureParent(
                                taskId,
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode());
        require(Objects.equals(parent, taskId), "实际组织不存在该检验任务");
        var chain = chain(type, taskId);
        if (chain.rows().isEmpty())
            return new View(
                    "LEGACY_UNAVAILABLE",
                    type,
                    taskId,
                    TenantContextHolder.getTenantId(),
                    FactoryContextHolder.getFactoryCode(),
                    null,
                    null);
        var current = chain.rows().get(chain.rows().size() - 1).snapshot();
        var original = chain.rows().get(0).snapshot();
        return new View(
                "FROZEN",
                type,
                taskId,
                TenantContextHolder.getTenantId(),
                FactoryContextHolder.getFactoryCode(),
                current,
                "CREATE".equals(original.operation()) ? original : null);
    }

    private void ready() {
        try {
            require(Objects.equals(mapper.schemaVersion(), 1), "检验依据冻结结构尚未就绪，请完成对应数据库升级");
        } catch (org.springframework.dao.DataAccessException e) {
            throw new CheckedException("检验依据冻结结构尚未就绪，请完成对应数据库升级", e);
        }
    }

    private void scope(String type, Long id) {
        access.requireScope();
        type(type);
        require(id != null && id > 0, "检验依据缺少实际父任务编号");
        ready();
    }

    private static void type(String type) {
        require("PRODUCT".equals(type) || "PROCEDURE".equals(type), "不支持的生产检验类型");
    }

    private Snapshot decode(QmsInspectionBasisEntity row) {
        require(
                row.getPayload() != null
                        && row.getPayload().getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD
                        && Objects.equals(
                                QmsInspectionBasisJson.sha(row.getPayload()),
                                row.getPayloadFingerprint()),
                "检验依据原文指纹不一致");
        var s = QmsInspectionBasisJson.read(row.getPayload(), Snapshot.class);
        require(QmsInspectionBasisJson.write(s).equals(row.getPayload()), "检验依据原文不是本版本的确定性结构");
        require(
                s.schemaVersion() == 1
                        && s.task() != null
                        && s.plan() != null
                        && s.revision() > 0
                        && s.revision() <= 5000
                        && Objects.equals(s.task().tenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                s.task().factoryCode(), FactoryContextHolder.getFactoryCode())
                        && Objects.equals(s.task().tenantId(), row.getTenantId())
                        && Objects.equals(s.task().factoryCode(), row.getFactoryCode())
                        && Objects.equals(s.task().id(), row.getTaskId())
                        && Objects.equals(s.task().type(), row.getInspectionType())
                        && Objects.equals(s.operation(), row.getOperation())
                        && s.revision() == row.getRevision()
                        && Objects.equals(s.previousFingerprint(), row.getPreviousFingerprint())
                        && Objects.equals(s.createdBy(), row.getCreatedBy())
                        && row.getOccurredAt() != null
                        && s.occurredAt() == row.getOccurredAt().getTime(),
                "检验依据原文与实际索引身份不一致");
        type(s.task().type());
        require(
                Set.of("CREATE", "RECONFIGURE", "APPEND").contains(s.operation())
                        && ((s.revision() == 1 && s.previousFingerprint() == null)
                                || (s.revision() > 1
                                        && s.previousFingerprint() != null
                                        && s.previousFingerprint().matches("[0-9a-f]{64}"))),
                "检验依据动作或版本链无效");
        require(
                !"CREATE".equals(s.operation())
                        || (s.revision() == 1
                                && s.creationRequest() != null
                                && Objects.equals(
                                        QmsInspectionBasisJson.sha(s.creationRequest()),
                                        s.creationFingerprint())),
                "原始报检命令指纹损坏");
        require(
                "CREATE".equals(s.operation())
                        || (s.creationRequest() == null && s.creationFingerprint() == null),
                "非创建版本不能冒充原始报检命令");
        if ("CREATE".equals(s.operation())) {
            var command =
                    QmsInspectionBasisJson.read(
                            s.creationRequest(), com.fasterxml.jackson.databind.JsonNode.class);
            require(
                    command.isObject()
                            && command.has("sourceRequestId")
                            && command.has("inspectionNum")
                            && command.path("schemaVersion").asInt() == 1
                            && command.path("tenantId").asLong() == s.task().tenantId()
                            && command.path("factoryCode").asText().equals(s.task().factoryCode())
                            && command.path("inspectionType").asText().equals(s.task().type())
                            && command.path("num").asInt() == s.task().num()
                            && Objects.equals(
                                    command.get("sourceRequestId").isNull()
                                            ? null
                                            : command.get("sourceRequestId").asText(),
                                    s.task().sourceRequestId())
                            && Objects.equals(
                                    command.get("inspectionNum").isNull()
                                            ? null
                                            : command.get("inspectionNum").asInt(),
                                    s.plan().requestedCount()),
                    "原始创建命令与冻结来源或申报抽样量不一致");
        }
        if (s.productionContext() != null) {
            QmsInspectionProductionBasisService.matches(s.productionContext(), s.task());
            if ("CREATE".equals(s.operation())) {
                var original =
                        QmsInspectionBasisJson.read(
                                s.creationRequest(), com.fasterxml.jackson.databind.JsonNode.class);
                require(
                        Objects.equals(
                                        original.path("sourceRequestFingerprint").asText(),
                                        s.productionContext().commandFingerprint())
                                && Objects.equals(
                                        original.path("num").asText(),
                                        s.productionContext().inspectionQuantity()),
                        "原创建命令与生产依据指纹或批量不一致");
            }
        }
        validatePlan(s.task(), s.plan(), s.bindings());
        return s;
    }

    private static List<Binding> ordered(List<Binding> bindings) {
        require(
                bindings != null && !bindings.isEmpty() && bindings.size() <= 5000,
                "检验依据缺少有界的实际项目");
        var ids = new HashSet<Long>();
        var standardIds = new HashSet<Long>();
        for (var b : bindings)
            require(
                    b != null
                            && b.id() != null
                            && b.id() > 0
                            && b.standardItemId() != null
                            && b.standardItemId() > 0
                            && ids.add(b.id())
                            && standardIds.add(b.standardItemId()),
                    "检验依据的实际项目或标准项缺失、重复");
        return bindings.stream().sorted(Comparator.comparing(Binding::standardItemId)).toList();
    }

    private static void validatePlan(
            Task task, QmsFrozenInspectionPlan plan, List<Binding> bindings) {
        require(
                task != null
                        && task.id() != null
                        && task.id() > 0
                        && task.num() != null
                        && task.num() > 0
                        && plan != null
                        && Objects.equals(task.standardId(), plan.standardId())
                        && Objects.equals(task.standardName(), plan.standardName()),
                "冻结方案与实际标准或批量不一致");
        var actual = ordered(bindings);
        var targets = plan.targets(task.num());
        require(
                actual.size() == targets.size()
                        && Objects.equals(
                                task.inspectionNum(),
                                targets.stream()
                                        .mapToInt(QmsFrozenInspectionPlan.Target::count)
                                        .max()
                                        .orElseThrow()),
                "冻结方案与任务应检量不一致");
        for (int i = 0; i < targets.size(); i++) {
            var t = targets.get(i);
            var p = t.item();
            var b = actual.get(i);
            require(
                    Objects.equals(b.standardItemId(), p.standardItemId())
                            && Objects.equals(b.name(), p.name())
                            && Objects.equals(b.demand(), p.demand())
                            && Objects.equals(b.testMethod(), p.testMethod())
                            && Objects.equals(b.position(), p.position())
                            && Objects.equals(b.checkType(), p.checkType())
                            && Objects.equals(b.checkClassify(), p.checkClassify())
                            && Objects.equals(b.upperLimit(), p.upperLimit())
                            && Objects.equals(b.lowerLimit(), p.lowerLimit())
                            && Objects.equals(b.totalNum(), t.count())
                            && Objects.equals(b.ac(), t.ac())
                            && Objects.equals(b.re(), t.re()),
                    "冻结项目的判定、描述或抽样结果不一致");
        }
    }

    private static Task task(QmsInspectionProductTaskEntity t) {
        return new Task(
                "PRODUCT",
                t.getId(),
                t.getTenantId(),
                t.getFactoryCode(),
                t.getSourceTaskCode(),
                t.getSourceRequestId(),
                t.getMatCode(),
                t.getProcessCode(),
                null,
                t.getBatchNo(),
                t.getStandardId(),
                t.getStandardName(),
                t.getNum(),
                t.getInspectionNum(),
                t.getOrderNo(),
                t.getOrderId(),
                t.getDeptCode());
    }

    private static List<Binding> product(
            QmsInspectionProductTaskEntity task, List<QmsInspectionProductTaskDetailEntity> rows) {
        require(rows != null, "实际检验项目不可读取");
        for (var d : rows)
            require(
                    d != null
                            && Objects.equals(d.getTaskId(), task.getId())
                            && Objects.equals(d.getTenantId(), task.getTenantId())
                            && Objects.equals(d.getFactoryCode(), task.getFactoryCode()),
                    "检验项目不属于实际父任务或组织");
        return rows.stream()
                .map(
                        d ->
                                new Binding(
                                        d.getId(),
                                        d.getStandardItemId(),
                                        d.getName(),
                                        d.getDemand(),
                                        d.getMethod(),
                                        d.getPosition(),
                                        d.getCheckType(),
                                        d.getCheckClassify(),
                                        d.getUpperLimit(),
                                        d.getLowerLimit(),
                                        d.getTotalNum(),
                                        d.getAc(),
                                        d.getRe()))
                .toList();
    }

    private static Task task(QmsInspectionProcedureTaskEntity t) {
        return new Task(
                "PROCEDURE",
                t.getId(),
                t.getTenantId(),
                t.getFactoryCode(),
                t.getSourceTaskCode(),
                t.getSourceRequestId(),
                t.getMatCode(),
                t.getProcessCode(),
                t.getLabelType(),
                t.getBatchNo(),
                t.getStandardId(),
                t.getStandardName(),
                t.getNum(),
                t.getInspectionNum(),
                t.getOrderNo(),
                t.getOrderId(),
                t.getDeptCode());
    }

    private static List<Binding> procedure(
            QmsInspectionProcedureTaskEntity task,
            List<QmsInspectionProcedureTaskDetailEntity> rows) {
        require(rows != null, "实际检验项目不可读取");
        for (var d : rows)
            require(
                    d != null
                            && Objects.equals(d.getTaskId(), task.getId())
                            && Objects.equals(d.getTenantId(), task.getTenantId())
                            && Objects.equals(d.getFactoryCode(), task.getFactoryCode()),
                    "检验项目不属于实际父任务或组织");
        return rows.stream()
                .map(
                        d ->
                                new Binding(
                                        d.getId(),
                                        d.getStandardItemId(),
                                        d.getName(),
                                        d.getDemand(),
                                        d.getMethod(),
                                        d.getPosition(),
                                        d.getCheckType(),
                                        d.getCheckClassify(),
                                        d.getUpperLimit(),
                                        d.getLowerLimit(),
                                        d.getTotalNum(),
                                        d.getAc(),
                                        d.getRe()))
                .toList();
    }

    private record Verified(QmsInspectionBasisEntity row, Snapshot snapshot) {}

    private record Chain(List<Verified> rows, long bytes) {}

    private Chain chain(String type, Long taskId) {
        scope(type, taskId);
        var rows =
                mapper.lineage(
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        type,
                        taskId);
        require(rows != null, "检验依据链不可读取");
        if (rows.isEmpty()) return new Chain(List.of(), 0);
        var first = rows.get(0);
        require(
                first != null
                        && first.getLineageRows() != null
                        && first.getLineageRows() > 0
                        && first.getLineageRows() <= 5000
                        && first.getLineageRows() == rows.size()
                        && first.getLineageBytes() != null
                        && first.getLineageBytes() <= 8388608
                        && first.getLineageBytes() > 0,
                "检验依据链超过5000版或8MiB，或读取不完整，请先治理");
        var verified = new ArrayList<Verified>();
        String previous = null;
        int revision = 1;
        long bytes = 0;
        for (var row : rows) {
            require(
                    row != null
                            && Objects.equals(row.getTaskId(), taskId)
                            && Objects.equals(row.getInspectionType(), type)
                            && Objects.equals(row.getLineageRows(), first.getLineageRows())
                            && Objects.equals(row.getLineageBytes(), first.getLineageBytes())
                            && Objects.equals(row.getRevision(), revision)
                            && Objects.equals(row.getPreviousFingerprint(), previous),
                    "检验依据完整版本链缺失或前版指纹不一致");
            require(row.getPayload() != null, "检验依据原文不可读取或超过上限");
            bytes += row.getPayload().getBytes(StandardCharsets.UTF_8).length;
            require(bytes <= 8388608, "检验依据链累计超过8MiB，请先治理");
            var snapshot = decode(row);
            require(
                    revision != 1
                            || "CREATE".equals(snapshot.operation())
                            || "RECONFIGURE".equals(snapshot.operation()),
                    "检验依据首版动作无效");
            require(
                    verified.isEmpty()
                            || Objects.equals(
                                    verified.get(0).snapshot().productionContext(),
                                    snapshot.productionContext()),
                    "检验版本链不能增加、丢失或替换原生产依据");
            verified.add(new Verified(row, snapshot));
            previous = row.getPayloadFingerprint();
            revision++;
        }
        return new Chain(List.copyOf(verified), bytes);
    }
}
