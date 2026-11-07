package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.REDIRECTED_SCHEMA;
import static com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.SCHEMA;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.iot.transport.TransportProgress;
import com.kitex.mom.iot.transport.TransportProgress.*;
import com.kitex.mom.mes.dto.MesWipDeliverySimulationDTO.*;
import com.kitex.mom.mes.mapper.MesWipDeliverySimulationMapper;
import com.kitex.mom.mes.mapper.MesWipDeliverySimulationMapper.*;
import com.kitex.mom.mes.mapper.MesWipNextStationsMapper;
import com.kitex.mom.mes.mapper.MesWipTransferMapper;

import org.springframework.beans.factory.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Shadow simulation only. No stock/handover/provider mutation dependency is injected. */
@Service
public class MesWipDeliverySimulationService {
    private final MesActualFactoryAccessService access;
    private final MesWipDeliverySimulationMapper db;
    private final MesWipTransferMapper locks;
    private final MesWipNextStationsMapper origins;
    private final MesWipDeliveryCreationCheckService creationChecks;
    private final PermissionService permissions;
    private final BooleanSupplier enabled;
    private final MesWipRedirectEffects redirects;
    private final MesWipRedirectedDeliveryService redirected;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public MesWipDeliverySimulationService(
            MesActualFactoryAccessService access,
            MesWipDeliverySimulationMapper db,
            MesWipTransferMapper locks,
            MesWipNextStationsMapper origins,
            MesWipDeliveryCreationCheckService creationChecks,
            PermissionService permissions,
            @Value("${mom.mes.wip-delivery.simulation-enabled:false}") boolean enabled,
            MesWipRedirectEffects redirects,
            MesWipRedirectedDeliveryService redirected) {
        this(
                access,
                db,
                locks,
                origins,
                creationChecks,
                permissions,
                () -> enabled,
                redirects,
                redirected);
    }

    MesWipDeliverySimulationService(
            MesActualFactoryAccessService access,
            MesWipDeliverySimulationMapper db,
            MesWipTransferMapper locks,
            MesWipNextStationsMapper origins,
            MesWipDeliveryCreationCheckService creationChecks,
            PermissionService permissions,
            BooleanSupplier enabled,
            MesWipRedirectEffects redirects,
            MesWipRedirectedDeliveryService redirected) {
        this.access = access;
        this.db = db;
        this.locks = locks;
        this.origins = origins;
        this.creationChecks = creationChecks;
        this.permissions = permissions;
        this.enabled = enabled;
        this.redirects = redirects;
        this.redirected = redirected;
    }

    private record Scope(Long tenant, String factory, Long actor) {}

    private Scope scope(boolean write) {
        if (!permissions.hasPermission("mes_wipTransfer_dispatch"))
            throw new AccessDeniedException("没有配送协作权限");
        String f = access.require(write ? "write" : "read");
        var u = SecurityUtils.getUser();
        need(
                TenantContextHolder.getTenantId() != null
                        && u != null
                        && u.getId() != null
                        && Objects.equals(u.getTenantId(), TenantContextHolder.getTenantId()),
                "组织身份不一致");
        return new Scope(TenantContextHolder.getTenantId(), f, u.getId());
    }

    private boolean ready() {
        int n = db.installed();
        if (n == 0) return false;
        need(n == 3 && Objects.equals(db.ready(), 1), "配送模拟记录结构未就绪");
        return true;
    }

    private void requireReady() {
        need(ready(), "配送模拟记录尚未升级");
    }

    private boolean canWrite(Scope s) {
        try {
            return Objects.equals(access.require("write"), s.factory);
        } catch (RuntimeException deniedOrUnavailable) {
            return false;
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview(String transferId) {
        var s = scope(false);
        id(transferId);
        var tr = db.transfer(s.tenant, s.factory, Long.valueOf(transferId));
        need(tr != null, "未找到本工厂转运单");
        boolean r = ready();
        var effect = redirects.resolve(tr);
        var tasks =
                r
                        ? db.tasks(s.tenant, s.factory, s.actor, transferId).stream()
                                .map(
                                        t ->
                                                new Brief(
                                                        t.getTaskId(),
                                                        t.getCreatedAt().toString(),
                                                        t.getReason()))
                                .toList()
                        : List.<Brief>of();
        return new Overview(
                SCHEMA,
                s.tenant.toString(),
                s.factory,
                s.actor.toString(),
                enabled.getAsBoolean(),
                r,
                canWrite(s),
                new PhysicalTransfer(
                        transferId,
                        tr.getRevision().toString(),
                        tr.getStatus(),
                        decimal(tr.getRemainingQuantity()),
                        decimal(tr.getReceivedQuantity()),
                        tr.getMatUnit(),
                        new CurrentTarget(
                                effect == null ? "ORIGINAL" : "REDIRECTED",
                                effect == null
                                        ? tr.getToLocationCode()
                                        : effect.check().reference().point().code(),
                                effect == null ? null : effect.requestId(),
                                effect == null || redirected.ready())),
                tasks);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Task task(String taskId) {
        var s = scope(false);
        key(taskId);
        requireReady();
        return view(load(s, taskId, false));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Receipt inspect(Command c) {
        var s = scope(false);
        check(c, s);
        requireReady();
        return receipt(s, c);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt execute(Command c) {
        var s = scope(true);
        check(c, s);
        requireReady();
        need("read committed".equals(db.isolation()), "配送模拟写入要求READ_COMMITTED");
        locks.lockRequest(
                "MES-WIP-DELIVERY-SIM:" + s.tenant + ":" + s.factory + ":" + c.requestId());
        var old = receipt(s, c);
        if (!"NOT_FOUND".equals(old.state())) return old;
        need(enabled.getAsBoolean(), "当前工厂运行环境未启用配送模拟");
        TaskRow task;
        State before;
        State after;
        int seq;
        CreationCheck newCheck = null;
        com.kitex.mom.mes.dto.MesWipRedirectedDeliveryDTO.Target newRedirect = null;
        if ("CREATE".equals(c.kind())) {
            var tr = db.lockTransfer(s.tenant, s.factory, Long.valueOf(c.transferId()));
            need(
                    tr != null
                            && "TRANSIT".equals(tr.getStatus())
                            && tr.getRemainingQuantity() != null
                            && tr.getRemainingQuantity().signum() > 0
                            && Objects.equals(
                                    c.expectedRevision(), Objects.toString(tr.getRevision())),
                    "转运单版本、状态或剩余数量已变化，请重新核对");
            need(
                    tr.getSourceStockId() != null
                            && tr.getSourceReportId() != null
                            && present(tr.getArrangeCode())
                            && present(tr.getProduceTaskCode())
                            && present(tr.getMatCode())
                            && present(tr.getMatUnit())
                            && present(tr.getFromLocationCode())
                            && present(tr.getToLocationCode())
                            && !tr.getFromLocationCode().equals(tr.getToLocationCode()),
                    "转运单来源或库位依据不完整");
            var effect = redirects.resolve(tr);
            need(
                    (effect == null ? SCHEMA : REDIRECTED_SCHEMA).equals(c.schemaVersion()),
                    "配送目标已变化，请保留原号核对或终止后重新创建");
            newRedirect = effect == null ? null : redirected.create(tr);
            need(effect == null || newRedirect != null, "改向配送缺少创建复核");
            var routeTarget =
                    MesWipDeliveryBoundOrigin.freeze(
                            tr, origins.dispatch(s.tenant, s.factory, tr.getId()));
            var creationCheck =
                    newRedirect != null || routeTarget == null
                            ? null
                            : creationChecks.check(tr, routeTarget);
            need(newRedirect != null || routeTarget == null || creationCheck != null, "绑定配送缺少创建复核");
            newCheck = creationCheck;
            var frozen =
                    new Snapshot(
                            c.transferId(),
                            c.expectedRevision(),
                            tr.getSourceStockId().toString(),
                            tr.getSourceReportId().toString(),
                            tr.getArrangeCode(),
                            tr.getProduceTaskCode(),
                            tr.getMatCode(),
                            tr.getMatName(),
                            tr.getMatBatch(),
                            tr.getMatBarcode(),
                            tr.getMatUnit(),
                            decimal(tr.getRemainingQuantity()),
                            tr.getFromLocationCode(),
                            newRedirect == null
                                    ? tr.getToLocationCode()
                                    : newRedirect.check().reference().point().code(),
                            routeTarget,
                            creationCheck,
                            newRedirect);
            if (newRedirect != null) redirected.verify(frozen, s.tenant, s.factory, s.actor);
            if (creationCheck != null)
                MesWipDeliveryCreationCheckService.verify(
                        creationCheck, frozen, s.tenant.toString(), s.factory, s.actor.toString());
            task = new TaskRow();
            task.setTenantId(s.tenant);
            task.setFactoryCode(s.factory);
            task.setActorId(s.actor);
            task.setTaskId(c.taskId());
            task.setTransferId(c.transferId());
            task.setSnapshotJson(write(frozen));
            task.setSnapshotHash(hash(task.getSnapshotJson()));
            task.setReason(c.reason());
            task.setCreatedAt(System.currentTimeMillis());
            need(db.insertTask(task) == 1, "模拟任务保存失败");
            before = null;
            after = TransportProgress.initial();
            seq = 1;
        } else {
            task = load(s, c.taskId(), true);
            var v = view(task);
            need(c.schemaVersion().equals(v.schemaVersion()), "模拟协议与冻结任务不一致");
            need(c.transferId().equals(v.snapshot().transferId()), "原转运单不一致");
            need(c.expectedRevision().equals(v.revision()), "模拟任务版本已变化，请按原号核对后刷新");
            if (!Set.of("REQUEST_CANCEL", "CONFIRM_CANCEL").contains(c.kind())) {
                var current = db.lockTransfer(s.tenant, s.factory, Long.valueOf(c.transferId()));
                if (v.snapshot().redirectedTarget() != null)
                    redirected.progress(v.snapshot(), current);
                else if (current != null) redirects.requireOriginalTarget(current);
                else
                    need(
                            redirects.find(s.tenant, s.factory, c.transferId()) == null,
                            "原转运已改向生效，不能继续原目标模拟");
            }
            seq = v.events().size() + 1;
            need(seq <= 100, "本次模拟事件已达上限");
            before = new State(Phase.valueOf(v.phase()), v.unknown(), v.cancelRequested());
            after = TransportProgress.apply(before, Action.valueOf(c.kind()));
        }
        var e = new EventRow();
        e.setTenantId(s.tenant);
        e.setFactoryCode(s.factory);
        e.setActorId(s.actor);
        e.setTaskId(c.taskId());
        e.setRequestId(c.requestId());
        e.setSequenceNo(seq);
        e.setKind(c.kind());
        e.setCommandJson(write(c));
        e.setFingerprint(hash(e.getCommandJson()));
        e.setBeforeJson(write(before));
        e.setAfterJson(write(after));
        e.setCreatedAt(System.currentTimeMillis());
        if (newCheck != null)
            MesWipDeliveryCreationCheckService.verifyCreateTime(newCheck, e.getCreatedAt());
        if (newRedirect != null) MesWipRedirectedDeliveryFacts.time(newRedirect, e.getCreatedAt());
        need(db.insertEvent(e) == 1, "模拟事件保存失败");
        return proof(s, c, e);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(Cancel cancel) {
        need(cancel != null, "终止原请求不能为空");
        var c = cancel.original();
        var s = scope(true);
        check(c, s);
        requireReady();
        need("read committed".equals(db.isolation()), "配送模拟终止要求READ_COMMITTED");
        need(validReason(cancel.reason()), "请填写500字以内的终止原因");
        locks.lockRequest(
                "MES-WIP-DELIVERY-SIM:" + s.tenant + ":" + s.factory + ":" + c.requestId());
        var old = receipt(s, c);
        if (!"NOT_FOUND".equals(old.state())) return old;
        var row = new CancelRow();
        row.setTenantId(s.tenant);
        row.setFactoryCode(s.factory);
        row.setActorId(s.actor);
        row.setRequestId(c.requestId());
        row.setCommandJson(write(c));
        row.setFingerprint(hash(row.getCommandJson()));
        row.setReason(cancel.reason());
        row.setCanceledAt(System.currentTimeMillis());
        need(db.insertCancel(row) == 1, "终止原请求保存失败");
        return cancelled(s, c, row);
    }

    private Receipt cancelled(Scope s, Command c, CancelRow row) {
        return new Receipt(
                c.schemaVersion(),
                s.tenant.toString(),
                s.factory,
                s.actor.toString(),
                c.requestId(),
                row.getFingerprint(),
                "CANCELED",
                null,
                new Cancellation(row.getReason(), row.getCanceledAt().toString()));
    }

    private TaskRow load(Scope s, String id, boolean lock) {
        var t =
                lock
                        ? db.lockTask(s.tenant, s.factory, s.actor, id)
                        : db.task(s.tenant, s.factory, s.actor, id);
        need(t != null, "未找到本人模拟任务");
        return t;
    }

    private Receipt receipt(Scope s, Command c) {
        var list = db.request(s.tenant, s.factory, c.requestId());
        need(list.size() <= 1, "原请求存在重复记录");
        var cancellations = db.cancellations(s.tenant, s.factory, c.requestId());
        need(cancellations.size() <= 1 && (list.isEmpty() || cancellations.isEmpty()), "原请求存在冲突记录");
        if (!cancellations.isEmpty()) {
            var row = cancellations.get(0);
            need(
                    Objects.equals(row.getActorId(), s.actor)
                            && write(c).equals(row.getCommandJson())
                            && hash(row.getCommandJson()).equals(row.getFingerprint()),
                    "已终止原请求身份或内容不一致");
            return cancelled(s, c, row);
        }

        if (list.isEmpty())
            return new Receipt(
                    c.schemaVersion(),
                    c.tenantId(),
                    c.factoryCode(),
                    c.actorId(),
                    c.requestId(),
                    hash(write(c)),
                    "NOT_FOUND",
                    null,
                    null);
        var e = list.get(0);
        need(
                Objects.equals(e.getActorId(), s.actor)
                        && Objects.equals(e.getCommandJson(), write(c))
                        && Objects.equals(e.getFingerprint(), hash(write(c))),
                "原请求身份或内容不一致，不能换内容重试");
        var v = view(load(s, e.getTaskId(), false));
        need(v.events().stream().anyMatch(x -> x.requestId().equals(c.requestId())), "原事件未属于完整任务链");
        return proof(s, c, e);
    }

    private Receipt proof(Scope s, Command c, EventRow e) {
        return new Receipt(
                c.schemaVersion(),
                s.tenant.toString(),
                s.factory,
                s.actor.toString(),
                c.requestId(),
                e.getFingerprint(),
                "COMMITTED",
                projection(e, c, read(e.getAfterJson(), State.class)),
                null);
    }

    private Task view(TaskRow t) {
        need(hash(t.getSnapshotJson()).equals(t.getSnapshotHash()), "模拟任务原快照指纹不一致");
        MesWipDeliveryBoundOrigin.verifySnapshotJson(t.getSnapshotJson());
        MesWipDeliveryCreationCheckService.verifySnapshotJson(t.getSnapshotJson());
        var frozen = read(t.getSnapshotJson(), Snapshot.class);
        String schema = frozen.redirectedTarget() == null ? SCHEMA : REDIRECTED_SCHEMA;
        if (frozen.redirectedTarget() != null) {
            frozen = MesWipRedirectReviewCodec.parse(t.getSnapshotJson(), Snapshot.class);
            redirected.verify(frozen, t.getTenantId(), t.getFactoryCode(), t.getActorId());
        }
        if (frozen.routeTarget() != null)
            MesWipDeliveryBoundOrigin.verify(
                    frozen.routeTarget(),
                    t.getTenantId().toString(),
                    t.getFactoryCode(),
                    frozen.sourceStockId(),
                    frozen.sourceReportId(),
                    frozen.redirectedTarget() == null
                            ? frozen.toLocationCode()
                            : frozen.redirectedTarget().check().originalLocationCode());
        if (frozen.creationCheck() != null)
            MesWipDeliveryCreationCheckService.verify(
                    frozen.creationCheck(),
                    frozen,
                    t.getTenantId().toString(),
                    t.getFactoryCode(),
                    t.getActorId().toString());
        need(Objects.equals(frozen.transferId(), t.getTransferId()), "模拟任务来源不一致");
        var events = db.events(t.getTenantId(), t.getFactoryCode(), t.getTaskId());
        need(!events.isEmpty() && events.size() <= 100, "模拟事件链缺失或超限");
        State current = null;
        List<Event> out = new ArrayList<>();
        int seq = 0;
        for (var e : events) {
            seq++;
            var c = read(e.getCommandJson(), Command.class);
            var s = new Scope(t.getTenantId(), t.getFactoryCode(), t.getActorId());
            check(c, s);
            need(
                    schema.equals(c.schemaVersion())
                            && Objects.equals(e.getTenantId(), s.tenant)
                            && Objects.equals(e.getFactoryCode(), s.factory)
                            && Objects.equals(e.getActorId(), s.actor)
                            && Objects.equals(e.getTaskId(), t.getTaskId())
                            && Objects.equals(c.taskId(), t.getTaskId())
                            && Objects.equals(c.transferId(), frozen.transferId())
                            && Objects.equals(e.getRequestId(), c.requestId())
                            && Objects.equals(e.getKind(), c.kind())
                            && Objects.equals(e.getSequenceNo(), seq)
                            && hash(e.getCommandJson()).equals(e.getFingerprint())
                            && write(c).equals(e.getCommandJson())
                            && write(current).equals(e.getBeforeJson()),
                    "模拟事件原文或顺序不一致");
            if (seq == 1) {
                need(
                        "CREATE".equals(c.kind())
                                && c.expectedRevision().equals(frozen.transferRevision())
                                && c.reason().equals(t.getReason()),
                        "模拟创建原件不一致");
                if (frozen.creationCheck() != null)
                    MesWipDeliveryCreationCheckService.verifyCreateTime(
                            frozen.creationCheck(), e.getCreatedAt());
                if (frozen.redirectedTarget() != null)
                    MesWipRedirectedDeliveryFacts.time(frozen.redirectedTarget(), e.getCreatedAt());
                current = TransportProgress.initial();
            } else {
                need(
                        !"CREATE".equals(c.kind())
                                && c.expectedRevision().equals(Integer.toString(seq - 1)),
                        "模拟事件原版本不一致");
                current = TransportProgress.apply(current, Action.valueOf(c.kind()));
            }
            need(write(current).equals(e.getAfterJson()), "模拟事件结果不一致");
            out.add(projection(e, c, current));
        }
        return new Task(
                schema,
                t.getTenantId().toString(),
                t.getFactoryCode(),
                t.getActorId().toString(),
                t.getTaskId(),
                "SIMULATION",
                frozen,
                Integer.toString(seq),
                current.confirmed().name(),
                current.unknown(),
                current.cancelRequested(),
                current.status(),
                out);
    }

    private Event projection(EventRow e, Command c, State s) {
        return new Event(
                e.getTaskId(),
                c.transferId(),
                e.getRequestId(),
                e.getSequenceNo().toString(),
                e.getKind(),
                c.reason(),
                e.getCreatedAt().toString(),
                s.confirmed().name(),
                s.unknown(),
                s.cancelRequested());
    }

    private void check(Command c, Scope s) {
        need(
                c != null
                        && (SCHEMA.equals(c.schemaVersion())
                                || REDIRECTED_SCHEMA.equals(c.schemaVersion()))
                        && s.tenant.toString().equals(c.tenantId())
                        && s.factory.equals(c.factoryCode())
                        && s.actor.toString().equals(c.actorId()),
                "模拟原请求身份或工厂不一致");
        key(c.requestId());
        key(c.taskId());
        id(c.transferId());
        id(c.expectedRevision());
        need(validReason(c.reason()), "请填写500字以内的模拟依据");
        if ("CREATE".equals(c.kind())) need(c.taskId().equals(c.requestId()), "创建任务须沿原请求号");
        else {
            try {
                Action.valueOf(c.kind());
            } catch (Exception e) {
                throw new IllegalArgumentException("不支持的模拟事件");
            }
        }
    }

    private static boolean validReason(String text) {
        if (!present(text) || !text.equals(text.trim()) || text.length() > 500) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))
                    return false;
                i++;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static void key(String s) {
        need(s != null && s.matches("[A-Za-z0-9_-]{16,64}"), "原请求号格式不正确");
    }

    private static void id(String s) {
        need(s != null && s.matches("[1-9][0-9]{0,18}"), "ID或版本须为完整正整数");
        try {
            Long.parseLong(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("ID或版本超出范围");
        }
    }

    private static String decimal(BigDecimal n) {
        need(n != null, "转运数量未保存");
        return n.stripTrailingZeros().toPlainString();
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("模拟原件不能序列化", e);
        }
    }

    private <T> T read(String v, Class<T> c) {
        try {
            return json.readValue(v, c);
        } catch (Exception e) {
            throw new IllegalArgumentException("模拟原件损坏", e);
        }
    }

    private static String hash(String v) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("模拟原件指纹计算失败", e);
        }
    }

    private static void need(boolean ok, String m) {
        if (!ok) throw new IllegalArgumentException(m);
    }
}
