package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesShiftHandoverCommand;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 班次交接确认的是明确范围内的账面观察，不锁死后续报工/质量处置，也不再次写产量。
 */
@Service
@RequiredArgsConstructor
public class MesShiftHandoverService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .registerModule(new SimpleModule().addSerializer(Long.class, ToStringSerializer.instance).addSerializer(BigDecimal.class, ToStringSerializer.instance))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final MesActualFactoryAccessService access;
    private final MesShiftHandoverMapper handovers;
    private final MesShiftHandoverOperationMapper operations;
    private final MesTaskReportMapper reports;
    private final MesTaskReportUnqualifiedMapper rejects;
    private final MesTaskArrangeMapper arranges;
    private final MesLaborEntryMapper laborEntries;
    private final org.mybatis.spring.SqlSessionTemplate session;
    private final MesFormalHandoverSourceService formalSources;
    private final MesShiftLeftoverService leftovers;
    private final MesShiftCommandJournal commands;
    private final PlatformTransactionManager transactionManager;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Object preview(MesShiftHandoverCommand c) {
        String f = access.require("read");
        require(c != null, "交接预览不能为空");
        // 新预览由所选班次重新定位；正式提交则必须保持预览返回的精确窗口。
        c.setWindowStart(null);
        c.setWindowEnd(null);
        var formal = formalSources.resolve(f, c);
        c.setWindowStart(formal.windowStart());
        c.setWindowEnd(formal.windowEnd());
        normalizeWindow(c);
        var result = new LinkedHashMap<>(observe(f, c));
        result.put("formal", formal);
        return result;
    }

    public Object page(long page) {
        String f = access.require("read");
        require(page > 0 && page <= 100000, "页码无效");
        // 不在目录返回最大4MiB的逐条证据。
        return handovers.selectPage(new Page<>(page, 20), scope(f).select(MesShiftHandover::getId, MesShiftHandover::getShiftLabel,
                        MesShiftHandover::getWindowStart, MesShiftHandover::getWindowEnd, MesShiftHandover::getRevision, MesShiftHandover::getStatus,
                        MesShiftHandover::getApplicantId, MesShiftHandover::getReceiverId, MesShiftHandover::getSourceFingerprint, MesShiftHandover::getCreateTime)
                .orderByDesc(MesShiftHandover::getId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Object detail(Long id, boolean recheck) {
        String f = access.require("read");
        var h = required(id, f, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("handover", h);
        result.put("operations", operations.selectList(opScope(f)
                .eq(MesShiftHandoverOperation::getHandoverId, id).orderByAsc(MesShiftHandoverOperation::getId)));
        if (recheck) {
            var current = observe(f, command(h), hasLabor(h));
            result.put("current", current);
            result.put("unchanged", h.getSourceFingerprint().equals(current.get("fingerprint")));
            result.put("changes", changes(h.getSnapshotJson(), (String) current.get("snapshotJson")));
        }
        return result;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Object execute(String action, MesShiftHandoverCommand c) {
        String f = access.require("write");
        Long actor = SecurityUtils.getUser().getId();
        require(Set.of("SUBMIT", "ACCEPT", "REJECT", "WITHDRAW").contains(action), "交接动作无效");
        require(c != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}") && StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 1000, "须填写稳定请求号及1000字内实际交接/处理依据");
        c.setReason(c.getReason().trim());
        if ("SUBMIT".equals(action)) {
            normalizeWindow(c);
            require(c.getId() == null && StrUtil.isNotBlank(c.getShiftLabel()) && c.getShiftLabel().length() <= 100
                    && c.getExpectedFingerprint() != null && c.getExpectedFingerprint().matches("[a-f0-9]{64}"), "先明确班次名称并预览完整来源");
            c.setShiftLabel(c.getShiftLabel().trim());
        } else
            require(c.getId() != null && c.getWindowStart() == null && c.getWindowEnd() == null && c.getArrangeCodes() == null && c.getShiftLabel() == null && c.getExpectedFingerprint() == null
                    && Arrays.asList(c.getSourceShiftCode(), c.getSourceShiftDate(), c.getTargetShiftCode(), c.getTargetShiftDate(),
                    c.getDesignatedReceiverId(), c.getExpectedFormalFingerprint(), c.getSafetyNote(), c.getEquipmentNote(),
                    c.getQualityNote(), c.getMaterialNote(), c.getLeftoverItems(), c.getCarryForwardIds()).stream().allMatch(Objects::isNull), "处理交接版本不能夹带范围或内容变更");
        String fingerprint = hash(json(Arrays.asList(action, c, actor)));
        // 先独立持久占号，再创建一致性业务快照，不能把占号一起回滚或落在旧快照之外。
        commands.reserve("HANDOVER", f, c.getRequestId(), action, fingerprint);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(ignored -> executeReserved(action, c, f, actor, fingerprint));
    }

    private Object executeReserved(String action, MesShiftHandoverCommand c, String f, Long actor, String fingerprint) {
        Object completed = commands.begin("HANDOVER", f, c.getRequestId(), action, fingerprint);
        if (completed != null) return completed;
        handovers.lockKey("MES-SHIFT-REQUEST:" + TenantContextHolder.getTenantId() + ":" + f + ":" + c.getRequestId());
        session.clearCache();
        var replay = operations.selectOne(opScope(f).eq(MesShiftHandoverOperation::getRequestId, c.getRequestId()));
        if (replay != null) {
            require(fingerprint.equals(replay.getFingerprint()), "同一请求号不能换动作、内容或人员");
            return commands.succeed("HANDOVER", f, c.getRequestId(), Map.of("handover", required(replay.getHandoverId(), f, false), "operation", replay, "replayed", true));
        }
        MesShiftHandover h;
        String observed = null;
        if ("SUBMIT".equals(action)) {
            var formal = formalSources.resolve(f, c);
            require(formal.fingerprint().equals(c.getExpectedFormalFingerprint())
                            && formal.windowStart().equals(c.getWindowStart()) && formal.windowEnd().equals(c.getWindowEnd()),
                    "班次、接班资格、现场声明或在制观察已变化，请重新预览确认");
            String key = hash(json(Arrays.asList(c.getWindowStart(), c.getWindowEnd(), c.getArrangeCodes())));
            handovers.lockKey("MES-SHIFT-SCOPE:" + TenantContextHolder.getTenantId() + ":" + f + ":" + key);
            session.clearCache();
            var previous = handovers.selectOne(scope(f).eq(MesShiftHandover::getScopeKey, key).orderByDesc(MesShiftHandover::getRevision).last("LIMIT 1 FOR UPDATE"));
            require(previous == null || !"PENDING".equals(previous.getStatus()), "同一时间窗/派工范围已有待接班版本，先接收、驳回或撤回");
            var evidence = observe(f, c);
            observed = (String) evidence.get("fingerprint");
            require(observed.equals(c.getExpectedFingerprint()), "报工、冲销或质量事实已变化，请重新预览，不能按旧汇总交班");
            h = new MesShiftHandover();
            h.setTenantId(TenantContextHolder.getTenantId());
            h.setFactoryCode(f);
            h.setScopeKey(key);
            h.setShiftLabel(c.getShiftLabel());
            h.setWindowStart(c.getWindowStart());
            h.setWindowEnd(c.getWindowEnd());
            h.setArrangeCodesJson(json(c.getArrangeCodes()));
            h.setPreviousId(previous == null ? null : previous.getId());
            h.setRevision(previous == null ? 1 : Math.addExact(previous.getRevision(), 1));
            h.setSnapshotJson((String) evidence.get("snapshotJson"));
            h.setSourceFingerprint(observed);
            h.setReason(c.getReason());
            h.setApplicantId(actor);
            h.setStatus("PENDING");
            h.setFormalSnapshotJson(formal.snapshotJson());
            h.setFormalFingerprint(formal.fingerprint());
            h.setDesignatedReceiverId(Long.valueOf(formal.receiverId()));
            require(handovers.insert(h) == 1, "交班证据保存失败");
            leftovers.record(h, c);
        } else {
            h = required(c.getId(), f, true);
            require("PENDING".equals(h.getStatus()), "仅待接班版本可处理，历史版本不覆盖");
            if ("WITHDRAW".equals(action)) require(actor.equals(h.getApplicantId()), "仅交班人可撤回");
            else {
                require(!actor.equals(h.getApplicantId()), "接班/驳回须由另一名授权人员操作");
                formalSources.requireReceiver(h, f);
            }
            if ("ACCEPT".equals(action)) {
                var evidence = observe(f, command(h), hasLabor(h));
                observed = (String) evidence.get("fingerprint");
                require(h.getSourceFingerprint().equals(observed), "交班后来源事实已变化，请核对变化、驳回后重新交班，不以旧数接班");
            }
            h.setStatus("ACCEPT".equals(action) ? "ACCEPTED" : "REJECT".equals(action) ? "REJECTED" : "WITHDRAWN");
            h.setReceiverId(actor);
            h.setReceiveReason(c.getReason());
            require(handovers.updateById(h) == 1, "交接处理保存失败");
        }
        var op = new MesShiftHandoverOperation();
        op.setTenantId(h.getTenantId());
        op.setFactoryCode(f);
        op.setHandoverId(h.getId());
        op.setRequestId(c.getRequestId());
        op.setFingerprint(fingerprint);
        op.setAction(action);
        op.setResultStatus(h.getStatus());
        op.setOperatorId(actor);
        op.setReason(c.getReason());
        op.setObservedFingerprint(observed);
        require(operations.insert(op) == 1, "交接永久回执保存失败");
        return commands.succeed("HANDOVER", f, c.getRequestId(), Map.of("handover", h, "operation", op, "replayed", false));
    }

    /**
     * 响应丢失时只读找原请求，不重做业务、不换号推断失败。
     */
    public Object requestReceipt(String requestId) {
        String factory = access.require("read");
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "请求号无效");
        Object receipt = commands.receipt("HANDOVER", factory, requestId);
        if (receipt != null) return receipt;
        var original = operations.selectOne(opScope(factory).eq(MesShiftHandoverOperation::getRequestId, requestId));
        if (original == null) return null;
        require(Objects.equals(original.getOperatorId(), SecurityUtils.getUser().getId()), "仅原操作者可核此命令回执");
        return Map.of("handover", required(original.getHandoverId(), factory, false), "operation", original, "replayed", true);
    }

    /**
     * 仅封存尚未执行的原号；已提交时返回真正成功回执，不反向修改交班状态。
     */
    public Object cancelUnexecuted(String requestId) {
        return commands.cancel("HANDOVER", access.require("write"), requestId);
    }

    private Map<String, Object> observe(String f, MesShiftHandoverCommand c) {
        return observe(f, c, true);
    }

    private boolean hasLabor(MesShiftHandover h) {
        try {
            String contract = JSON.readTree(h.getSnapshotJson()).path("contract").asText();
            require(Set.of("MES_SHIFT_BOOKED_V1", "MES_SHIFT_BOOKED_V2").contains(contract), "交接来源契约未知");
            return "MES_SHIFT_BOOKED_V2".equals(contract);
        } catch (CheckedException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckedException("交接历史证据无法读取");
        }
    }

    private Map<String, Object> observe(String f, MesShiftHandoverCommand c, boolean includeLabor) {
        normalizeWindow(c);
        var selected = arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>().eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, f).in(MesTaskArrangeEntity::getCode, c.getArrangeCodes()).orderByAsc(MesTaskArrangeEntity::getCode));
        require(selected.size() == c.getArrangeCodes().size() && selected.stream().map(MesTaskArrangeEntity::getCode).distinct().count() == selected.size(), "派工范围含不存在、重复或不可访问来源，不能漏选后交班");
        require(reports.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MesTaskReportEntity::getFactoryCode, f).in(MesTaskReportEntity::getArrangeCode, c.getArrangeCodes()).isNull(MesTaskReportEntity::getCreateTime)) == 0
                        && rejects.selectCount(new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>().eq(MesTaskReportUnqualifiedEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MesTaskReportUnqualifiedEntity::getFactoryCode, f).in(MesTaskReportUnqualifiedEntity::getArrangeCode, c.getArrangeCodes()).isNull(MesTaskReportUnqualifiedEntity::getCreateTime)) == 0,
                "所选派工存在无入账时间的历史记录，无法确定班次归属，请先治理来源，不静默遗漏");
        var r = reports.selectList(new LambdaQueryWrapper<MesTaskReportEntity>().eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, f).in(MesTaskReportEntity::getArrangeCode, c.getArrangeCodes()).ge(MesTaskReportEntity::getCreateTime, c.getWindowStart())
                .lt(MesTaskReportEntity::getCreateTime, c.getWindowEnd()).orderByAsc(MesTaskReportEntity::getId).last("LIMIT 5001"));
        var u = rejects.selectList(new LambdaQueryWrapper<MesTaskReportUnqualifiedEntity>().eq(MesTaskReportUnqualifiedEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportUnqualifiedEntity::getFactoryCode, f).in(MesTaskReportUnqualifiedEntity::getArrangeCode, c.getArrangeCodes()).ge(MesTaskReportUnqualifiedEntity::getCreateTime, c.getWindowStart())
                .lt(MesTaskReportUnqualifiedEntity::getCreateTime, c.getWindowEnd()).orderByAsc(MesTaskReportUnqualifiedEntity::getId).last("LIMIT 5001"));
        require(r.size() <= 5000 && u.size() <= 5000, "来源超过单类5000条上限，请明确拆分派工范围，不截断数量");
        Map<String, MesTaskArrangeEntity> identities = new HashMap<>();
        selected.forEach(a -> identities.put(a.getCode(), a));
        require(r.stream().allMatch(row -> sourceMatches(identities.get(row.getArrangeCode()), row.getTaskCode(), row.getProduceTaskCode(), row.getMatCode()))
                        && u.stream().allMatch(row -> sourceMatches(identities.get(row.getArrangeCode()), row.getTaskCode(), row.getProduceTaskCode(), row.getMatCode())),
                "报工来源与派工工单/工序/物料身份不一致，请先核对来源，不合并成正常交接");
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("contract", includeLabor ? "MES_SHIFT_BOOKED_V2" : "MES_SHIFT_BOOKED_V1");
        snapshot.put("factoryCode", f);
        snapshot.put("tenantId", String.valueOf(TenantContextHolder.getTenantId()));
        snapshot.put("windowStart", c.getWindowStart());
        snapshot.put("windowEnd", c.getWindowEnd());
        snapshot.put("arrangeCodes", c.getArrangeCodes());
        // 只冻结身份，不让仍在生产中的累计数量和状态把无关时段汇总持续判成陈旧。
        snapshot.put("arranges", selected.stream().map(a -> {
            Map<String, Object> row = new TreeMap<>();
            row.put("id", String.valueOf(a.getId()));
            row.put("code", a.getCode());
            row.put("taskCode", a.getTaskCode());
            row.put("produceTaskCode", a.getProduceTaskCode());
            row.put("matCode", a.getMatCode());
            row.put("routeCode", a.getRouteCode());
            row.put("routeWorkCenter", a.getRouteWorkCenter());
            return row;
        }).toList());
        snapshot.put("reports", r);
        snapshot.put("unqualified", u);
        if (includeLabor) {
            var labor = laborEntries.selectList(new LambdaQueryWrapper<MesLaborEntry>().eq(MesLaborEntry::getTenantId, TenantContextHolder.getTenantId()).eq(MesLaborEntry::getFactoryCode, f)
                    .in(MesLaborEntry::getArrangeCode, c.getArrangeCodes()).in(MesLaborEntry::getStatus, "APPROVED", "VOID_PENDING")
                    .lt(MesLaborEntry::getLaborStart, c.getWindowEnd()).gt(MesLaborEntry::getLaborEnd, c.getWindowStart()).orderByAsc(MesLaborEntry::getId).last("LIMIT 5001"));
            require(labor.size() <= 5000, "有效工时超过5000条，请明确拆组，不截断");
            Map<String, Map<String, Object>> totals = new TreeMap<>();
            for (var l : labor) {
                var a = identities.get(l.getArrangeCode());
                require(a != null && Objects.equals(a.getId(), l.getArrangeId())
                        && Objects.equals(a.getTaskCode(), l.getTaskCode()) && Objects.equals(a.getProduceTaskCode(), l.getProduceTaskCode()), "工时与派工来源不一致");
                require(l.getLaborStart() != null && l.getLaborEnd() != null && l.getDurationSeconds() != null && l.getDurationSeconds() > 0
                        && Duration.between(l.getLaborStart(), l.getLaborEnd()).getSeconds() == l.getDurationSeconds(), "已批准工时秒数不守恒，不能汇总");
                var start = l.getLaborStart().isAfter(c.getWindowStart()) ? l.getLaborStart() : c.getWindowStart();
                var end = l.getLaborEnd().isBefore(c.getWindowEnd()) ? l.getLaborEnd() : c.getWindowEnd();
                long seconds = Duration.between(start, end).getSeconds();
                var g = totals.computeIfAbsent(String.valueOf(l.getWorkerId()), key -> {
                    Map<String, Object> row = new TreeMap<>();
                    row.put("workerId", key);
                    row.put("workerCode", l.getWorkerCode());
                    row.put("seconds", 0L);
                    row.put("voidPendingSeconds", 0L);
                    return row;
                });
                g.put("seconds", Math.addExact((Long) g.get("seconds"), seconds));
                if ("VOID_PENDING".equals(l.getStatus()))
                    g.put("voidPendingSeconds", Math.addExact((Long) g.get("voidPendingSeconds"), seconds));
            }
            snapshot.put("laborEntries", labor);
            snapshot.put("laborSummary", totals.values());
            snapshot.put("laborNotice", "工时按本人已批准实际区间与班次窗口交集计秒，与产量createTime入账归属不同；待作废仍有效，待审不计入。没有记录表示未批准申报，不代表无人工作；不自动分摊产量。");
        }
        var summary = summarize(r, u);
        snapshot.put("summary", summary);
        String frozen = json(snapshot);
        require(frozen.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4 * 1024 * 1024, "完整交接证据超过4MiB，请明确拆组");
        return Map.of("snapshotJson", frozen, "fingerprint", hash(frozen), "summary", JSON.valueToTree(summary), "observedAt", LocalDateTime.now(),
                "notice", "按记录创建时间createTime的[start,end)归属；reportTime及设备事件时间仅作核对，迟到补传归实际入账班次。质量数为观察时状态。不同派工/物料/单位/模式不混加；独立不合格上报不与报工质量拒绝数相加。接班仅确认本次账面观察，不证明实物或多人实际工时。");
    }

    private Object summarize(List<MesTaskReportEntity> reports, List<MesTaskReportUnqualifiedEntity> rejects) {
        Map<String, Map<String, Object>> groups = new TreeMap<>();
        List<String> gaps = new ArrayList<>();
        for (var r : reports) {
            String key = json(Arrays.asList(r.getArrangeCode(), r.getProduceTaskCode(), r.getMatCode(), r.getReportUnit(), r.getReportMode(), StrUtil.isBlank(r.getReportUnit()) ? r.getId() : null));
            var g = groups.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("arrangeCode", r.getArrangeCode());
                m.put("produceTaskCode", r.getProduceTaskCode());
                m.put("matCode", r.getMatCode());
                m.put("unit", StrUtil.blankToDefault(r.getReportUnit(), "历史单位未冻结"));
                m.put("mode", StrUtil.blankToDefault(r.getReportMode(), "历史模式未冻结"));
                m.put("legacyReportId", StrUtil.isBlank(r.getReportUnit()) ? String.valueOf(r.getId()) : null);
                for (String n : List.of("declared", "effective", "reversed", "unknownStatus", "released", "pending", "rejected"))
                    m.put(n, BigDecimal.ZERO);
                m.put("qualityComplete", true);
                return m;
            });
            if (StrUtil.isBlank(r.getReportUnit()))
                gaps.add("报工" + r.getId() + "未冻结单位，独立列示不与其他记录合计");
            if (r.getReportNum() == null || r.getReportNum().signum() < 0) {
                g.put("qualityComplete", false);
                gaps.add("报工" + r.getId() + "数量无效，未计数");
                continue;
            }
            add(g, "declared", r.getReportNum());
            if ("REVERSED".equals(r.getStatus())) {
                add(g, "reversed", r.getReportNum());
                continue;
            }
            if (!"EFFECTIVE".equals(r.getStatus())) {
                g.put("qualityComplete", false);
                add(g, "unknownStatus", r.getReportNum());
                gaps.add("报工" + r.getId() + "状态未知，未计有效数");
                continue;
            }
            add(g, "effective", r.getReportNum());
            var quantities = Arrays.asList(r.getQualityReleasedNum(), r.getQualityPendingNum(), r.getQualityRejectedNum());
            if (quantities.stream().anyMatch(q -> q == null || q.signum() < 0) || quantities.stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(r.getReportNum()) != 0) {
                g.put("qualityComplete", false);
                gaps.add("报工" + r.getId() + "质量分量缺失/不守恒，本组质量数仅为可核验小计");
                continue;
            }
            add(g, "released", quantities.get(0));
            add(g, "pending", quantities.get(1));
            add(g, "rejected", quantities.get(2));
        }
        // 独立不合格记录无冻结单位，不合并到已报工质量拒绝数，也不计算FPY。
        return Map.of("groups", groups.values(), "reportCount", reports.size(), "unqualifiedRecordCount", rejects.size(), "gaps", gaps,
                "unqualifiedNotice", "独立不合格上报逐条查阅原始证据，不与质量拒绝重复加总；无冻结单位不跨记录汇总。");
    }

    private void add(Map<String, Object> row, String key, BigDecimal n) {
        row.put(key, ((BigDecimal) row.get(key)).add(n));
    }

    private boolean sourceMatches(MesTaskArrangeEntity a, String task, String process, String material) {
        return a != null && StrUtil.isNotBlank(task) && StrUtil.isNotBlank(process) && StrUtil.isNotBlank(material)
                && Objects.equals(task, a.getTaskCode()) && Objects.equals(process, a.getProduceTaskCode()) && Objects.equals(material, a.getMatCode());
    }

    private Object changes(String oldJson, String newJson) {
        try {
            var old = JSON.readTree(oldJson);
            var now = JSON.readTree(newJson);
            Map<String, Object> result = new LinkedHashMap<>();
            for (String name : List.of("reports", "unqualified", "laborEntries")) {
                Map<String, String> before = new TreeMap<>(), after = new TreeMap<>();
                old.path(name).forEach(n -> before.put(n.path("id").asText(), n.toString()));
                now.path(name).forEach(n -> after.put(n.path("id").asText(), n.toString()));
                result.put(name, Map.of("added", after.keySet().stream().filter(k -> !before.containsKey(k)).toList(), "removed", before.keySet().stream().filter(k -> !after.containsKey(k)).toList(),
                        "changed", before.keySet().stream().filter(k -> after.containsKey(k) && !before.get(k).equals(after.get(k))).toList()));
            }
            result.put("arrangeIdentityChanged", !old.path("arranges").equals(now.path("arranges")));
            return result;
        } catch (Exception e) {
            throw new CheckedException("历史证据不可解析，不能宣称无变化");
        }
    }

    private MesShiftHandoverCommand command(MesShiftHandover h) {
        var c = new MesShiftHandoverCommand();
        c.setWindowStart(h.getWindowStart());
        c.setWindowEnd(h.getWindowEnd());
        try {
            c.setArrangeCodes(JSON.readValue(h.getArrangeCodesJson(), JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception e) {
            throw new CheckedException("交接范围证据损坏");
        }
        return c;
    }

    private void normalizeWindow(MesShiftHandoverCommand c) {
        require(c != null && c.getWindowStart() != null && c.getWindowEnd() != null, "请明确班次起止时间");
        long seconds = Duration.between(c.getWindowStart(), c.getWindowEnd()).getSeconds();
        require(seconds > 0 && seconds <= 86400 && c.getWindowStart().getNano() == 0 && c.getWindowEnd().getNano() == 0
                && !c.getWindowEnd().isAfter(LocalDateTime.now()), "仅能交接已结束且不超过24小时的整秒时间窗，跨午夜请填写实际结束日期");
        require(c.getArrangeCodes() != null && !c.getArrangeCodes().isEmpty() && c.getArrangeCodes().size() <= 100 && c.getArrangeCodes().stream().allMatch(a -> StrUtil.isNotBlank(a) && a.trim().length() <= 100), "明确选择1至100个派工单，不自动扩展全厂");
        var codes = c.getArrangeCodes().stream().map(String::trim).sorted().toList();
        require(new HashSet<>(codes).size() == codes.size(), "派工范围不可重复");
        c.setArrangeCodes(codes);
    }

    private MesShiftHandover required(Long id, String f, boolean lock) {
        var q = scope(f).eq(MesShiftHandover::getId, id);
        if (lock) q.last("FOR UPDATE");
        var h = handovers.selectOne(q);
        require(h != null, "当前工厂交接版本不存在");
        return h;
    }

    private LambdaQueryWrapper<MesShiftHandover> scope(String f) {
        return new LambdaQueryWrapper<MesShiftHandover>().eq(MesShiftHandover::getTenantId, TenantContextHolder.getTenantId()).eq(MesShiftHandover::getFactoryCode, f);
    }

    private LambdaQueryWrapper<MesShiftHandoverOperation> opScope(String f) {
        return new LambdaQueryWrapper<MesShiftHandoverOperation>().eq(MesShiftHandoverOperation::getTenantId, TenantContextHolder.getTenantId()).eq(MesShiftHandoverOperation::getFactoryCode, f);
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new CheckedException("交接证据不能完整序列化");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
