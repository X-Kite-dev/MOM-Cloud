package com.kitex.mom.mdm.service.impl;

import static com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.SCHEMA;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.component.PermissionService;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.*;
import com.kitex.mom.mdm.mapper.MdmReceivingLocationMapper;
import com.kitex.mom.mdm.mapper.MdmReceivingLocationMapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MdmReceivingLocationService {
    private final MdmReceivingLocationMapper db;
    private final PermissionService permissions;
    private final ObjectMapper json = new ObjectMapper();

    private record Scope(Long tenant, String factory, Long actor, List<String> rights) {}

    private Scope scope(String required) {
        var u = SecurityUtils.getUser();
        Long t = TenantContextHolder.getTenantId();
        String f = FactoryContextHolder.getFactoryCode();
        need(
                u != null
                        && u.getId() != null
                        && u.getId() > 0
                        && t != null
                        && t > 0
                        && Objects.equals(u.getTenantId(), t)
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                        && text(f),
                "须以实名实际租户选择授权工厂");
        need(db.factoryCount(t, f) == 1, "实际启用工厂不唯一或不存在");
        var rights = db.permissions(t, f, u.getId());
        need(allowed(rights, required), "当前用户没有实际工厂" + required + "权限");
        return new Scope(t, f, u.getId(), rights);
    }

    private boolean allowed(List<String> r, String p) {
        return r != null
                && r.stream()
                        .anyMatch(
                                v ->
                                        "admin".equals(v)
                                                || "write".equals(v)
                                                || ("read".equals(p) && "read".equals(v)));
    }

    private void menu(boolean write) {
        if (!permissions.hasPermission(write ? "mdm_factoryModel_edit" : "mdm_factoryModel_view"))
            throw new AccessDeniedException("没有工厂建模维护/查看权限");
    }

    private boolean ready() {
        if (db.installed() == 0) return false;
        need(Objects.equals(db.ready(), 1), "收货位置结构未就绪");
        return true;
    }

    private void requireReady() {
        need(ready(), "收货位置配置尚未升级");
    }

    private Station station(PathRow p) {
        return new Station(
                str(p.getId()),
                p.getCode(),
                p.getName(),
                str(p.getLineId()),
                p.getLineCode(),
                str(p.getWorkshopId()),
                p.getWorkshopCode(),
                str(p.getFactoryId()),
                str(p.getIndustryId()),
                Boolean.TRUE.equals(p.getHealthy()));
    }

    private Point point(PointRow p) {
        return new Point(
                str(p.getId()),
                p.getCode(),
                p.getName(),
                str(p.getWarehouseId()),
                p.getWarehouseCode(),
                p.getWarehouseName(),
                str(p.getAreaId()),
                str(p.getShelfId()),
                Boolean.TRUE.equals(p.getHealthy()));
    }

    private PathRow path(Scope s, String code) {
        need(validText(code, 100), "工位编码格式不正确");
        var list = db.path(s.tenant, s.factory, code);
        need(list.size() == 1, "工位路径不存在或编码不唯一");
        return list.get(0);
    }

    private PathRow pathId(Scope scope, String stationId) {
        var rows = db.pathId(scope.tenant, scope.factory, id(stationId, false));
        need(rows.size() == 1, "所选工位身份不存在或不属于实际工厂");
        return rows.get(0);
    }

    private Reference reference(Scope s, PathRow station, List<PointRow> points) {
        need(
                station != null
                        && Boolean.TRUE.equals(station.getHealthy())
                        && points.size() == 1
                        && Boolean.TRUE.equals(points.get(0).getHealthy()),
                "工位/仓库/三级叶库位已停用、归属不明或不唯一");
        var st = station(station);
        var p = point(points.get(0));
        need(
                p.id() != null
                        && p.warehouseId() != null
                        && p.areaId() != null
                        && p.shelfId() != null,
                "库位层级不完整");
        return new Reference(
                st,
                p,
                com.kitex.mom.mdm.support.MdmReceivingReferenceFingerprint.of(
                        str(s.tenant), s.factory, st, p));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview(String stationId) {
        menu(false);
        var s = scope("read");
        var p = pathId(s, stationId);
        boolean r = ready();
        var list = r ? db.current(s.tenant, s.factory, p.getId()) : List.<Row>of();
        need(list.size() <= 100, "此工位收货位置超过维护范围");
        List<Binding> bindings = new ArrayList<>();
        Map<Long, List<PointRow>> points = new HashMap<>();
        if (!list.isEmpty())
            for (var pt :
                    db.points(s.tenant, s.factory, list.stream().map(Row::getLocationId).toList()))
                points.computeIfAbsent(pt.getId(), k -> new ArrayList<>()).add(pt);
        for (var row : list) {
            var c = change(row);
            Reference current = null;
            String issue = null;
            try {
                current = reference(s, p, points.getOrDefault(row.getLocationId(), List.of()));
                if (!current.hash().equals(c.reference().hash())) issue = "工位或位置结构已变化，须重新核对";
            } catch (RuntimeException ex) {
                issue = "工位或位置当前不可用";
            }
            bindings.add(new Binding(c, current, issue));
        }
        return new Overview(
                SCHEMA,
                str(s.tenant),
                s.factory,
                str(s.actor),
                station(p),
                r,
                allowed(s.rights, "write") && permissions.hasPermission("mdm_factoryModel_edit"),
                bindings);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReferenceView reference(String stationId, String location) {
        menu(false);
        var s = scope("read");
        var p = pathId(s, stationId);
        return new ReferenceView(
                SCHEMA,
                str(s.tenant),
                s.factory,
                str(s.actor),
                reference(s, p, db.point(s.tenant, s.factory, id(location, false))));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Points locations(String stationId, String search, String after) {
        menu(false);
        var s = scope("read");
        var p = pathId(s, stationId);
        need(station(p).valid(), "当前工位路径已停用");
        String q = search == null ? "" : search.trim();
        need(validOptional(q, 100), "查询内容不正确");
        Long cursor = after == null ? null : id(after, false);
        String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = db.candidates(s.tenant, s.factory, pattern, cursor);
        need(rows.size() <= 21, "库位查询超出范围");
        long previous = cursor == null ? 0 : cursor;
        for (var row : rows) {
            need(row.getId() > previous, "库位标识不唯一");
            previous = row.getId();
        }
        var page = rows.stream().limit(20).map(this::point).toList();
        return new Points(
                SCHEMA,
                str(s.tenant),
                s.factory,
                str(s.actor),
                station(p),
                q,
                after,
                page,
                rows.size() > 20 ? page.get(page.size() - 1).id() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RuntimeView runtime(String code) {
        var s = scope("read");
        var p = path(s, code);
        need(station(p).valid(), "工位当前未启用");
        boolean r = ready();
        List<RuntimeBinding> result = new ArrayList<>();
        if (r) {
            var rows = db.current(s.tenant, s.factory, p.getId());
            need(rows.size() <= 100, "工位收货位置数量超出范围");
            Map<Long, List<PointRow>> points = new HashMap<>();
            if (!rows.isEmpty())
                for (var v :
                        db.points(
                                s.tenant,
                                s.factory,
                                rows.stream().map(Row::getLocationId).toList()))
                    points.computeIfAbsent(v.getId(), k -> new ArrayList<>()).add(v);
            for (var row : rows) {
                var c = change(row);
                if (!"ACTIVE".equals(c.state())) continue;
                Reference current;
                try {
                    current = reference(s, p, points.getOrDefault(row.getLocationId(), List.of()));
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                if (current.hash().equals(c.reference().hash()))
                    result.add(
                            new RuntimeBinding(
                                    c.stationId(), c.locationId(), c.revision(), current));
            }
        }
        return new RuntimeView(
                SCHEMA, str(s.tenant), s.factory, str(s.actor), station(p), r, result);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History history(String station, String location, String before) {
        menu(false);
        var s = scope("read");
        requireReady();
        var rows =
                db.history(
                        s.tenant,
                        s.factory,
                        id(station, false),
                        id(location, false),
                        before == null ? null : id(before, false));
        var page = rows.stream().limit(20).map(this::change).toList();
        return new History(
                SCHEMA,
                str(s.tenant),
                s.factory,
                str(s.actor),
                station,
                location,
                before,
                page,
                rows.size() > 20 ? page.get(page.size() - 1).revision() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Receipt inspect(Command c) {
        menu(false);
        var s = scope("read");
        check(c, s);
        requireReady();
        return receipt(c, db.request(s.tenant, s.factory, c.requestId()));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt execute(Command c) {
        menu(true);
        var s = scope("write");
        check(c, s);
        requireReady();
        need("read committed".equals(db.isolation()), "配置写入须使用READ_COMMITTED");
        db.lock(requestLock(c));
        var existing = db.request(s.tenant, s.factory, c.requestId());
        if (existing != null) return receipt(c, existing);
        db.lock(pairLock(c));
        var last =
                db.latest(s.tenant, s.factory, id(c.stationId(), false), id(c.locationId(), false));
        long previous = last == null ? 0 : last.getRevision();
        need(Long.toString(previous).equals(c.expectedRevision()), "配置版本已变化，请重新核对");
        need(previous < Long.MAX_VALUE, "配置版本超出范围");
        Reference ref;
        if ("ACTIVE".equals(c.state())) {
            var locked = db.lockPath(s.tenant, s.factory, id(c.stationId(), false));
            need(locked.size() == 1, "工位路径已变化");
            var p = locked.get(0);
            need(path(s, p.getCode()).getId().equals(p.getId()), "工位编码不唯一");
            need(db.lockPoint(s.tenant, s.factory, id(c.locationId(), false)) != null, "库位不存在");
            ref = reference(s, p, db.point(s.tenant, s.factory, id(c.locationId(), false)));
            need(ref.hash().equals(c.referenceHash()), "工位或位置结构已变化，请重新选择");
            if (last == null)
                need(db.current(s.tenant, s.factory, p.getId()).size() < 100, "工位收货位置数量已达维护上限");
        } else {
            need(last != null, "尚无可停用的关系");
            var prior = change(last);
            need(prior.reference().hash().equals(c.referenceHash()), "原关系依据已变化");
            ref = prior.reference();
        }
        var row = row(c, "APPLIED");
        row.setRevision(previous + 1);
        row.setState(c.state());
        row.setSnapshotJson(write(ref));
        row.setSnapshotHash(sha(row.getSnapshotJson()));
        row.setReason(c.reason());
        need(db.insert(row) == 1, "收货位置记录保存失败");
        return receipt(c, row);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Receipt cancel(Cancel request) {
        menu(true);
        var s = scope("write");
        need(request != null, "须提供原命令");
        var c = request.original();
        check(c, s);
        need(validText(request.reason(), 500), "终止原因不正确");
        requireReady();
        need("read committed".equals(db.isolation()), "终止原请求须使用READ_COMMITTED");
        db.lock(requestLock(c));
        var old = db.request(s.tenant, s.factory, c.requestId());
        if (old != null) return receipt(c, old);
        var row = row(c, "CANCELED");
        row.setReason(request.reason());
        need(db.insert(row) == 1, "终止原请求保存失败");
        return receipt(c, row);
    }

    private Row row(Command c, String outcome) {
        var r = new Row();
        r.setTenantId(Long.valueOf(c.tenantId()));
        r.setFactoryCode(c.factoryCode());
        r.setActorId(Long.valueOf(c.actorId()));
        r.setStationId(Long.valueOf(c.stationId()));
        r.setLocationId(Long.valueOf(c.locationId()));
        r.setRequestId(c.requestId());
        r.setCommandJson(write(c));
        r.setFingerprint(sha(r.getCommandJson()));
        r.setOutcome(outcome);
        r.setRecordedAt(System.currentTimeMillis());
        return r;
    }

    private Receipt receipt(Command c, Row r) {
        if (r == null)
            return new Receipt(
                    SCHEMA,
                    c.tenantId(),
                    c.factoryCode(),
                    c.actorId(),
                    c.requestId(),
                    sha(write(c)),
                    "NOT_FOUND",
                    null,
                    null);
        need(
                write(c).equals(r.getCommandJson())
                        && sha(r.getCommandJson()).equals(r.getFingerprint())
                        && Objects.equals(r.getActorId(), Long.valueOf(c.actorId()))
                        && Objects.equals(r.getTenantId(), Long.valueOf(c.tenantId()))
                        && Objects.equals(r.getFactoryCode(), c.factoryCode())
                        && Objects.equals(r.getRequestId(), c.requestId()),
                "原请求身份或内容不一致");
        if ("APPLIED".equals(r.getOutcome()))
            return new Receipt(
                    SCHEMA,
                    c.tenantId(),
                    c.factoryCode(),
                    c.actorId(),
                    c.requestId(),
                    r.getFingerprint(),
                    "APPLIED",
                    change(r),
                    null);
        need(
                "CANCELED".equals(r.getOutcome())
                        && r.getRevision() == null
                        && validText(r.getReason(), 500),
                "原请求终止记录损坏");
        return new Receipt(
                SCHEMA,
                c.tenantId(),
                c.factoryCode(),
                c.actorId(),
                c.requestId(),
                r.getFingerprint(),
                "CANCELED",
                null,
                new Cancellation(r.getReason(), str(r.getRecordedAt())));
    }

    private Change change(Row r) {
        var c = read(r.getCommandJson(), Command.class);
        check(c, new Scope(r.getTenantId(), r.getFactoryCode(), r.getActorId(), List.of()));
        need(
                "APPLIED".equals(r.getOutcome())
                        && Objects.equals(r.getRequestId(), c.requestId())
                        && write(c).equals(r.getCommandJson())
                        && sha(r.getCommandJson()).equals(r.getFingerprint())
                        && r.getRevision() != null
                        && r.getRevision() > 0
                        && c.expectedRevision().equals(Long.toString(r.getRevision() - 1))
                        && Objects.equals(c.stationId(), str(r.getStationId()))
                        && Objects.equals(c.locationId(), str(r.getLocationId()))
                        && Objects.equals(c.state(), r.getState())
                        && Objects.equals(c.reason(), r.getReason())
                        && sha(r.getSnapshotJson()).equals(r.getSnapshotHash()),
                "配置版本原件损坏");
        var ref = read(r.getSnapshotJson(), Reference.class);
        need(
                ref.station().id().equals(c.stationId())
                        && ref.point().id().equals(c.locationId())
                        && ref.hash().equals(c.referenceHash()),
                "配置来源快照不一致");
        return new Change(
                c.stationId(),
                c.locationId(),
                str(r.getRevision()),
                c.state(),
                ref,
                c.reason(),
                c.actorId(),
                str(r.getRecordedAt()));
    }

    private void check(Command c, Scope s) {
        need(
                c != null
                        && SCHEMA.equals(c.schemaVersion())
                        && Objects.equals(c.tenantId(), str(s.tenant))
                        && Objects.equals(c.factoryCode(), s.factory)
                        && Objects.equals(c.actorId(), str(s.actor)),
                "配置原请求实名或实际组织不一致");
        id(c.stationId(), false);
        id(c.locationId(), false);
        id(c.expectedRevision(), true);
        need(
                c.requestId() != null
                        && c.requestId().matches("[A-Za-z0-9_-]{16,64}")
                        && Set.of("ACTIVE", "INACTIVE").contains(Objects.toString(c.state(), ""))
                        && c.referenceHash() != null
                        && c.referenceHash().matches("[0-9a-f]{64}")
                        && validText(c.reason(), 500),
                "配置原请求内容不完整或包含无效文本");
    }

    private String requestLock(Command c) {
        return "MDM-RECEIVING-REQUEST:"
                + c.tenantId()
                + ":"
                + c.factoryCode()
                + ":"
                + c.requestId();
    }

    private String pairLock(Command c) {
        return "MDM-RECEIVING-PAIR:"
                + c.tenantId()
                + ":"
                + c.factoryCode()
                + ":"
                + c.stationId()
                + ":"
                + c.locationId();
    }

    private static Long id(String v, boolean zero) {
        need(
                v != null && v.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}"),
                "ID/版本须为完整Long文本");
        try {
            return Long.valueOf(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("ID/版本超出范围");
        }
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean validText(String s, int max) {
        return text(s) && s.equals(s.trim()) && validOptional(s, max);
    }

    private static boolean validOptional(String s, int max) {
        if (s == null || s.length() > max) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 && c != 9 && c != 10 && c != 13) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalArgumentException("原件不能序列化", e);
        }
    }

    private <T> T read(String v, Class<T> t) {
        try {
            return json.readValue(v, t);
        } catch (Exception e) {
            throw new IllegalArgumentException("原件不能解析", e);
        }
    }

    private static String sha(String v) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(v.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("原件指纹失败", e);
        }
    }

    private static void need(boolean ok, String msg) {
        if (!ok) throw new IllegalArgumentException(msg);
    }
}
