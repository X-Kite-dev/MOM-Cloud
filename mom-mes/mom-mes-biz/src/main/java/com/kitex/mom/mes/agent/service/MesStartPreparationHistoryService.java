package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.agent.dto.MesStartPreparationDTO;
import com.kitex.mom.mes.entity.MesStartPreparationCheck;
import com.kitex.mom.mes.mapper.MesStartPreparationCheckMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 开工准备原核验历史。仅比较本人在同一派工上保存的观察，不补采、不签收或放行。
 * 当前账号的SOP与现场资格不能由另一账号的历史检查代替。
 */
@Service
@RequiredArgsConstructor
public class MesStartPreparationHistoryService {
    private static final int PAGE_SIZE = 10;
    private static final List<String> GATES = List.of("TERMINAL_COMMAND", "DISPATCH_STATE", "ASSIGNED_WORKER",
            "DISPATCH_RETURN", "TOOLING", "SOP", "ANDON_RELEASE", "REPAIR_RELEASE", "PREDECESSOR",
            "CHANGE_POINT", "DEVICE", "WORKSTATION", "MATERIAL");
    private static final Set<String> STATES = Set.of("READY", "BLOCKED", "UNKNOWN", "NOT_APPLICABLE");
    private final MesStartPreparationCheckMapper snapshots;
    private final MesStartPreparationQueryService original;
    private final ObjectMapper json;

    /** 锚点先沿原snapshot进行当前工厂READ/派工/操作者核验，再读取同范围历史，不触发观察。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History history(Long snapshotId, int current) {
        require(current > 0 && current <= 100000, "核验历史页码无效");
        Saved anchor = anchor(snapshotId);
        var page = snapshots.selectPage(new Page<>(current, PAGE_SIZE), scope()
                .eq(MesStartPreparationCheck::getArrangeId, anchor.row().getArrangeId())
                .eq(MesStartPreparationCheck::getTaskCode, anchor.row().getTaskCode())
                .lt(MesStartPreparationCheck::getObservedAt, anchor.row().getObservedAt())
                .orderByDesc(MesStartPreparationCheck::getObservedAt).orderByDesc(MesStartPreparationCheck::getId));
        List<Snapshot> records = new ArrayList<>();
        for (var row : page.getRecords()) {
            Saved saved = saved(row, original.decode(row));
            require(saved.time().isBefore(anchor.time()), "历史核验时间与原锚点不一致");
            records.add(summary(saved));
        }
        return new History("MES_START_PREPARATION_HISTORY_V1", summary(anchor), current, PAGE_SIZE,
                Long.toString(page.getTotal()), List.copyOf(records));
    }

    /** 比较原十三项的状态和依据是否变化；未知、不适用或原因变化不换算为改善或开工资格。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Comparison compare(Long snapshotId, Long baselineId) {
        Saved after = anchor(snapshotId);
        require(baselineId != null && baselineId > 0 && !baselineId.equals(snapshotId), "请选择更早的原核验记录");
        var row = snapshots.selectOne(scope().eq(MesStartPreparationCheck::getId, baselineId)
                .eq(MesStartPreparationCheck::getArrangeId, after.row().getArrangeId())
                .eq(MesStartPreparationCheck::getTaskCode, after.row().getTaskCode()));
        require(row != null, "原核验不属于本人、同一派工或当前工厂");
        Saved before = saved(row, original.decode(row));
        require(before.time().isBefore(after.time()) && row.getObservedAt().isBefore(after.row().getObservedAt()),
                "基线必须严格早于本次所选核验");
        require(Objects.equals(before.row().getContractVersion(), after.row().getContractVersion()),
                "两次核验合同不同，不能按当前口径比较");
        List<GateComparison> changes = new ArrayList<>();
        for (String code : GATES) {
            Gate first = before.gates().get(code), last = after.gates().get(code);
            require(first.title().equals(last.title()) && first.responsibleRole().equals(last.responsibleRole()),
                    "两次核验项口径或责任岗位已变化，不能直接比较");
            boolean statusChanged = !first.status().equals(last.status());
            boolean factChanged = !first.fingerprint().equals(last.fingerprint());
            changes.add(new GateComparison(code, last.title(), last.responsibleRole(), first.status(), last.status(),
                    first.reason(), last.reason(), statusChanged ? "STATUS_CHANGED" : factChanged ? "EVIDENCE_CHANGED" : "UNCHANGED",
                    Set.of("BLOCKED", "UNKNOWN").contains(last.status())));
        }
        return new Comparison("MES_START_PREPARATION_COMPARISON_V1", summary(before), summary(after), List.copyOf(changes));
    }

    private Saved anchor(Long id) {
        require(id != null && id > 0, "请从已保存的开工准备核验查看历史");
        var row = snapshots.selectOne(scope().eq(MesStartPreparationCheck::getId, id));
        require(row != null, "本人在当前工厂的核验记录不存在");
        var query = new MesStartPreparationDTO.SnapshotQuery();
        query.setFactoryCode(FactoryContextHolder.getFactoryCode());
        query.setSnapshotId(id.toString());
        return saved(row, original.snapshot(query));
    }

    /** 每次查询都从实际认证上下文取scope，不信任浏览器传入operator或tenant。 */
    private LambdaQueryWrapper<MesStartPreparationCheck> scope() {
        var actor = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(actor != null && actor.getId() != null && actor.getId() > 0 && tenant != null && tenant > 0
                        && Objects.equals(actor.getTenantId(), tenant) && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && factory != null && !factory.isBlank(),
                "核验历史须在本人真实租户工厂内读取");
        return new LambdaQueryWrapper<MesStartPreparationCheck>().eq(MesStartPreparationCheck::getTenantId, tenant)
                .eq(MesStartPreparationCheck::getFactoryCode, factory).eq(MesStartPreparationCheck::getOperatorId, actor.getId());
    }

    /** 原指纹验证之外再核表行/JSON根信封，避免借其他观察的元数据展示本次原事实。 */
    private Saved saved(MesStartPreparationCheck row, Map<String, Object> value) {
        JsonNode d = json.valueToTree(value);
        require(row.getId().toString().equals(text(d, "snapshotId")) && row.getTenantId().toString().equals(text(d, "tenantId"))
                        && row.getFactoryCode().equals(text(d, "factoryCode")) && row.getOperatorId().toString().equals(text(d, "operatorId"))
                        && row.getArrangeId().toString().equals(text(d, "arrangeId")) && row.getArrangeCode().equals(text(d, "arrangeCode"))
                        && row.getTaskCode().equals(text(d, "taskCode")) && row.getContractVersion().equals(text(d, "toolCode"))
                        && row.getRequestId().equals(text(d, "requestId")) && row.getEvidenceFingerprint().equals(text(d, "evidenceFingerprint"))
                        && row.getEvidenceFingerprint().matches("[a-f0-9]{64}") && d.path("startPermission").isBoolean()
                        && !d.path("startPermission").booleanValue(), "原核验信封与保存记录不一致");
        LocalDateTime observed;
        try {
            observed = LocalDateTime.parse(text(d, "observedAt"));
        } catch (java.time.DateTimeException ex) {
            throw new CheckedException("原核验时间无法读取");
        }
        // PostgreSQL微秒精度可能对原纳秒时刻舍入；只容许不足一微秒的存储差异。
        require(row.getObservedAt() != null && Duration.between(observed, row.getObservedAt()).abs().compareTo(Duration.ofNanos(1000)) <= 0,
                "原核验观察时间与保存记录不一致");
        JsonNode checks = d.get("checks");
        require(checks != null && checks.isArray() && checks.size() == GATES.size(), "原核验未保存完整十三项，不能补零比较");
        Map<String, Gate> gates = new LinkedHashMap<>();
        for (JsonNode check : checks) {
            String code = text(check, "code"), status = text(check, "status");
            require(GATES.contains(code) && STATES.contains(status), "原核验项或状态暂不支持");
            String fingerprint = text(check, "sourceFingerprint");
            require(fingerprint.matches("[a-f0-9]{64}"), "原核验项依据摘要缺失");
            require(gates.putIfAbsent(code, new Gate(text(check, "title"), status, text(check, "reason"),
                    text(check, "responsibleRole"), fingerprint)) == null, "原核验项重复");
        }
        require(gates.keySet().containsAll(GATES), "原核验项缺失");
        String status = text(d.path("summary"), "overallStatus");
        String expected = gates.values().stream().anyMatch(gate -> "BLOCKED".equals(gate.status())) ? "BLOCKED"
                : gates.values().stream().anyMatch(gate -> "UNKNOWN".equals(gate.status())) ? "UNKNOWN" : "READY";
        require(expected.equals(status) && expected.equals(row.getStatus()), "原核验总状态与十三项事实不一致");
        return new Saved(row, observed, gates);
    }

    private Snapshot summary(Saved saved) {
        var row = saved.row();
        return new Snapshot(row.getId().toString(), row.getTenantId().toString(), row.getFactoryCode(), row.getOperatorId().toString(),
                row.getArrangeId().toString(), row.getArrangeCode(), row.getTaskCode(), row.getContractVersion(), row.getEvidenceFingerprint(),
                saved.time().toString(), row.getStatus());
    }

    private String text(JsonNode row, String key) {
        JsonNode value = row.get(key);
        require(value != null && value.isTextual() && !value.textValue().isBlank(), "原核验必要字段缺失：" + key);
        return value.textValue();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    private record Gate(String title, String status, String reason, String responsibleRole, String fingerprint) { }
    private record Saved(MesStartPreparationCheck row, LocalDateTime time, Map<String, Gate> gates) { }

    /** 所有身份均为精确文本；不提供原请求号、原facts或模型内容。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(String id, String tenantId, String factoryCode, String operatorId, String arrangeId,
                           String arrangeCode, String taskCode, String contract, String evidenceFingerprint,
                           String observedAt, String status) { }
    public record History(String version, Snapshot anchor, int current, int pageSize, String total, List<Snapshot> records) { }
    public record GateComparison(String code, String title, String responsibleRole, String beforeStatus, String afterStatus,
                                 String beforeReason, String afterReason, String change, boolean needsHandling) { }
    public record Comparison(String version, Snapshot before, Snapshot after, List<GateComparison> checks) { }
}
