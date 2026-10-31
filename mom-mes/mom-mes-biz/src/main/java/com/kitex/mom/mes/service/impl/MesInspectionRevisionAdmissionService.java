package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesInspectionRevisionAdmissionDTO;
import com.kitex.mom.mes.dto.MesInspectionRevisionDeliveryDTO;
import com.kitex.mom.mes.entity.MesInspectionRevisionAdmissionEntity;
import com.kitex.mom.mes.entity.MesInspectionRevisionCancelEntity;
import com.kitex.mom.mes.mapper.MesInspectionEventStateMapper;
import com.kitex.mom.mes.mapper.MesInspectionRevisionAdmissionMapper;
import com.kitex.mom.mes.mapper.MesInspectionRevisionCancelMapper;
import com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;

/**
 * 独立签名意图的准入/取消；没有修改QMS结果、应用事件或清除冲突的路径。
 */
@Service
@RequiredArgsConstructor
public class MesInspectionRevisionAdmissionService {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final MesInspectionRevisionAdmissionMapper admissions;
    private final MesInspectionRevisionCancelMapper cancels;
    private final MesProductionTaskMutex mutex;
    private final MesInspectionRevisionHold holds;
    private final MesInspectionEventStateMapper states;
    private final MesInspectionEventInboxService inbox;
    private final MesInspectionEventProjection projection;
    private final MesQualityGateSourceEvidence sources;

    @Value("${mom.qms-inspection-revision.delivery-secret:}")
    private String deliverySecret;

    private Clock clock = Clock.systemUTC();

    private static long count(Map<String, Object> values, String name) {
        require(values != null && values.get(name) instanceof Number, "零产出范围不可核验");
        long n = ((Number) values.get(name)).longValue();
        require(n >= 0, "零产出范围出现负数");
        return n;
    }

    private static void zero(Object n) {
        require(n instanceof Number && new BigDecimal(n.toString()).signum() == 0, "工单产出数量缺失或非零");
    }

    private static JsonNode tree(String raw) {
        try {
            var j = JSON.readTree(raw);
            require(j != null && j.isObject(), "准入原件须为完整对象");
            return j;
        } catch (Exception e) {
            throw new CheckedException("准入原件不能严格解析", e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new CheckedException("准入原件不能冻结", e);
        }
    }

    private static String hash(String raw) {
        return QmsInspectionRevisionExecutionContract.fingerprint(raw);
    }

    private static Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private static String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private static void require(boolean c, String m) {
        if (!c) throw new CheckedException(m);
    }

    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public MesInspectionRevisionAdmissionDTO receive(MesInspectionRevisionDeliveryDTO d) {
        var intent = authenticate(d);
        holds.installed();
        var request = intent.request();
        String root = request.path("source").path("orderNo").textValue();
        mutex.lock(root);
        var a =
                admissions.selectOne(
                        new LambdaQueryWrapper<MesInspectionRevisionAdmissionEntity>()
                                .eq(MesInspectionRevisionAdmissionEntity::getId, d.getRequestId())
                                .eq(MesInspectionRevisionAdmissionEntity::getTenantId, tenant())
                                .eq(
                                        MesInspectionRevisionAdmissionEntity::getFactoryCode,
                                        factory()));
        var c =
                cancels.selectOne(
                        new LambdaQueryWrapper<MesInspectionRevisionCancelEntity>()
                                .eq(MesInspectionRevisionCancelEntity::getId, d.getRequestId())
                                .eq(MesInspectionRevisionCancelEntity::getTenantId, tenant())
                                .eq(MesInspectionRevisionCancelEntity::getFactoryCode, factory()));
        if (a != null) audit(a, d, intent);
        if (c != null) audit(c, d, intent);
        if ("STATUS".equals(d.getOperation())) return receipt(d, intent, a, c);
        if ("CANCEL".equals(d.getOperation())) {
            String reason = d.getReason().strip();
            if (c != null) {
                require(
                        c.getCanceledBy().equals(d.getOperatorId()) && c.getReason().equals(reason),
                        "取消原件已存在，不能换人或换件覆盖");
                return receipt(d, intent, a, c);
            }
            require(
                    admissions.sealed(d.getRequestId(), tenant(), factory()) == 0,
                    "已进入正式修订，不能从准备入口取消，请恢复正式流程");
            Date now = Date.from(clock.instant());
            require(a == null || !now.before(a.getReservedAt()), "本机时钟早于已留存准入时间，请先校准时钟");
            c =
                    new MesInspectionRevisionCancelEntity()
                            .setId(d.getRequestId())
                            .setTenantId(tenant())
                            .setFactoryCode(factory())
                            .setTaskCode(root)
                            .setProduceTaskCode(
                                    request.path("source").path("sourceTaskCode").textValue())
                            .setInspectionId(Long.valueOf(request.path("taskId").textValue()))
                            .setIntent(d.getIntent())
                            .setIntentFingerprint(d.getIntentFingerprint())
                            .setCanceledBy(d.getOperatorId())
                            .setCanceledAt(now)
                            .setReason(reason);
            require(cancels.insert(c) == 1, "取消原件保存失败，未释放冻结");
            return receipt(d, intent, a, c);
        }
        if (c != null || a != null) return receipt(d, intent, a, c);
        require(!holds.held(root), "当前生产工单已有其他修订冻结");
        var records =
                admissions.bindings(
                        tenant(), factory(), Long.valueOf(request.path("taskId").textValue()));
        require(records.size() == 1, "当前组织缺少唯一的检验生成绑定");
        var observed = records.get(0);
        var process = sources.parent(observed.getProduceTaskCode());
        var owner = sources.root(process);
        require(
                root.equals(owner.getCode())
                        && root.equals(observed.getTaskCode())
                        && process.getCode()
                        .equals(request.path("source").path("sourceTaskCode").textValue()),
                "执行意图与实际生产工单归属不一致");
        require(
                states.lockRoot(tenant(), factory(), root).equals(List.of(owner.getId())),
                "准入根工单已变化");
        require(
                states.lockProcess(tenant(), factory(), process.getCode())
                        .equals(List.of(process.getId())),
                "准入工序已变化");
        var locked = states.lockGenerations(tenant(), factory(), process.getCode());
        require(locked.size() <= 5000, "准入工序检验记录超限");
        var record =
                locked.stream()
                        .filter(g -> Objects.equals(g.getId(), observed.getId()))
                        .findFirst()
                        .orElse(null);
        require(
                record != null
                        && Boolean.TRUE.equals(record.getQualityEventManaged())
                        && "FIRST".equals(record.getInspectionType())
                        && Objects.equals(record.getInspectionId(), observed.getInspectionId()),
                "首版准入只支持已接管的实际首检");
        var state = projection.binding(record, true);
        require(
                "ACTIVE".equals(state.getStatus())
                        && state.getLastSequence() == 2
                        && state.getLastEventId()
                        .toString()
                        .equals(request.path("baseEventId").textValue())
                        && state.getLastFingerprint()
                        .equals(request.path("baseFingerprint").textValue())
                        && request.path("baseSequence").longValue() == 2,
                "准入须精确指向首次结果的已应用原件");
        var chain = inbox.localChain(record);
        require(
                chain.size() == 2
                        && "CREATE".equals(chain.get(0).getEventType())
                        && "RESULT".equals(chain.get(1).getEventType()),
                "准入存在待应用后件或非首次结果");
        var base = inbox.verified(chain.get(1));
        require(
                base.getId().equals(state.getLastEventId())
                        && base.getFingerprint().equals(state.getLastFingerprint()),
                "准入原件与应用游标不一致");
        var original = tree(base.getPayload());
        require(original.get("source").equals(request.get("source")), "修订冻结来源与MES原件不一致");
        var task = inbox.source(base);
        require(sources.verify(record, task).rootPrimaryKeyFrozen(), "准入须有永久冻结的生产根主键，不能按旧编码推定");
        var footprint = states.applicationFootprint(record.getId(), tenant(), factory());
        require(
                footprint != null
                        && footprint.get("event_count") instanceof Number
                        && ((Number) footprint.get("event_count")).longValue() == 2,
                "首次检验正式应用账不完整");
        var applicationFingerprints = new ArrayList<String>();
        for (var originalEvent : chain) {
            var application = states.application(originalEvent.getId(), tenant(), factory());
            require(
                    application != null
                            && application.getAppliedAt() != null
                            && application.getDecisionSnapshot() != null
                            && application
                            .getDecisionSnapshot()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            .length
                            <= 1048576
                            && hash(application.getDecisionSnapshot())
                            .equals(application.getDecisionFingerprint()),
                    "历史应用原件摘要或时间不完整");
            var originalReceipt = projection.applicationOriginal(record, application);
            require(originalReceipt.getId().equals(originalEvent.getId()), "历史应用与连续原件不一致");
            var frozen = tree(application.getDecisionSnapshot());
            require(
                    frozen.path("schemaVersion").isInt()
                            && frozen.get("schemaVersion").intValue() == 1
                            && originalEvent
                            .getId()
                            .toString()
                            .equals(frozen.path("eventId").textValue())
                            && record.getId()
                            .toString()
                            .equals(frozen.path("genRecordId").textValue())
                            && originalEvent
                            .getFingerprint()
                            .equals(frozen.path("eventFingerprint").textValue())
                            && frozen.path("decision").isObject(),
                    "历史应用原件封套不一致");
            applicationFingerprints.add(application.getDecisionFingerprint());
        }
        var resolution = projection.resolve(record, true, null, null, null);
        require(
                resolution != null
                        && resolution.task() != null
                        && "ACTIVE".equals(resolution.status())
                        && resolution.eventId().equals(base.getId()),
                "MES正式应用原件或冻结决策不能核验");
        require(
                "QUALIFIED".equals(task.getResult())
                        && "FINISHED".equals(task.getStatus())
                        && task.getNum() != null
                        && task.getNum().equals(task.getUsableNum())
                        && task.getNum().equals(task.getHandleNum())
                        && task.getHandleResult() == null
                        && "UN_QUALIFIED".equals(request.path("targetResult").textValue())
                        && request.path("targetUsable").intValue() == 0
                        && request.path("targetHandled").intValue() == 0,
                "首版准入只接受未报工首检合格改为不合格");
        var rootFacts = admissions.rootFacts(tenant(), factory(), root);
        require(
                rootFacts != null
                        && Set.of("PRODUCT", "DISPATCH")
                        .contains(Objects.toString(rootFacts.get("status"), ""))
                        && !"REWORK".equals(rootFacts.get("resourceType")),
                "生产工单状态或返工来源不适用准入");
        zero(rootFacts.get("complete"));
        require(admissions.reports(tenant(), factory(), root) == 0, "生产工单已有报工历史，须另行处理既有产出");
        require(admissions.proofs(tenant(), factory(), root) == 0, "生产工单已有质量库存证明，不能按零产出准入");
        var processes = admissions.processFacts(tenant(), factory(), root);
        var arrangements = admissions.arrangementFacts(tenant(), factory(), root);
        long processCount = count(processes, "total"), arrangeCount = count(arrangements, "total");
        require(
                processCount > 0
                        && processCount <= 5000
                        && arrangeCount <= 5000
                        && count(processes, "invalid") == 0
                        && count(arrangements, "invalid") == 0,
                "工序或派工产出计数缺失、非零、超限或尚在生产，不能准入");
        require(
                Boolean.TRUE.equals(admissions.timingReady())
                        && admissions.activeTiming(tenant(), factory(), root) == 0,
                "请先正常暂停作业并关闭运行计时，再申请修订准入");
        Date now = Date.from(clock.instant());
        var proof = new TreeMap<String, Object>();
        proof.put("schemaVersion", 1);
        proof.put("requestId", d.getRequestId().toString());
        proof.put("tenantId", tenant().toString());
        proof.put("factoryCode", factory());
        proof.put("taskCode", root);
        proof.put("rootId", owner.getId().toString());
        proof.put("produceTaskCode", process.getCode());
        proof.put("processId", process.getId().toString());
        proof.put("genRecordId", record.getId().toString());
        proof.put("inspectionId", record.getInspectionId().toString());
        proof.put("baseEventId", base.getId().toString());
        proof.put("baseSequence", 2);
        proof.put("baseFingerprint", base.getFingerprint());
        proof.put("baseStateVersion", state.getVersion());
        proof.put("intentFingerprint", d.getIntentFingerprint());
        proof.put("applicationFingerprints", applicationFingerprints);
        proof.put("activeTimingCount", 0);
        proof.put("reportCount", 0);
        proof.put("qualityProofCount", 0);
        proof.put("processCount", processCount);
        proof.put("arrangementCount", arrangeCount);
        proof.put("rootCompleteQuantity", "0");
        proof.put("rootStatus", rootFacts.get("status"));
        proof.put("reservedAt", now.toInstant().toString());
        String snapshot = write(proof);
        a =
                new MesInspectionRevisionAdmissionEntity()
                        .setId(d.getRequestId())
                        .setTenantId(tenant())
                        .setFactoryCode(factory())
                        .setTaskCode(root)
                        .setProduceTaskCode(process.getCode())
                        .setGenRecordId(record.getId())
                        .setInspectionId(record.getInspectionId())
                        .setBaseEventId(base.getId())
                        .setBaseSequence(2L)
                        .setBaseFingerprint(base.getFingerprint())
                        .setBaseStateVersion(state.getVersion())
                        .setIntent(d.getIntent())
                        .setIntentFingerprint(d.getIntentFingerprint())
                        .setBasisSnapshot(snapshot)
                        .setBasisFingerprint(hash(snapshot))
                        .setReservedAt(now);
        require(admissions.insert(a) == 1, "准入原件保存失败，冻结已回滚");
        return receipt(d, intent, a, null);
    }

    private QmsInspectionRevisionExecutionContract.Parsed authenticate(
            MesInspectionRevisionDeliveryDTO d) {
        MesQualityGateSourceEvidence.scope();
        long now = clock.instant().getEpochSecond();
        require(
                d != null
                        && d.getRequestId() != null
                        && d.getRequestId() > 0
                        && tenant().equals(d.getTenantId())
                        && factory().equals(d.getFactoryCode())
                        && Set.of("RESERVE", "CANCEL", "STATUS")
                        .contains(Objects.toString(d.getOperation(), ""))
                        && d.getIssuedAt() != null
                        && d.getIssuedAt() >= now - 90
                        && d.getIssuedAt() <= now + 15
                        && d.getNonce() != null
                        && d.getNonce().matches("[A-Za-z0-9_-]{16,64}")
                        && d.getOperatorId() != null
                        && d.getOperatorId() > 0
                        && d.getIntent() != null
                        && d.getIntent().length() <= 131072
                        && hash(d.getIntent()).equals(d.getIntentFingerprint())
                        && d.signatureMatches(deliverySecret),
                "修订准入签名、实际组织、时窗或原文身份无效");
        var p = QmsInspectionRevisionExecutionContract.parse(d.getIntent());
        var q = p.request();
        require(
                q.path("requestId").textValue().equals(d.getRequestId().toString())
                        && q.path("tenantId").textValue().equals(tenant().toString())
                        && q.path("factoryCode").textValue().equals(factory())
                        && !p.requestedAt().isAfter(clock.instant().plusSeconds(15)),
                "执行意图不属于当前请求/组织或时间");
        if ("CANCEL".equals(d.getOperation()))
            require(
                    d.getReason() != null
                            && !d.getReason().isBlank()
                            && d.getReason().strip().length() <= 1000,
                    "取消须明确1至1000字符原因");
        else
            require(
                    d.getOperatorId().equals(p.executorId()) && d.getReason() == null,
                    "准入/查询操作人与冻结意图不一致");
        MesProductionTaskMutex.key(
                tenant(), factory(), q.path("source").path("orderNo").textValue());
        return p;
    }

    void audit(
            MesInspectionRevisionAdmissionEntity a,
            MesInspectionRevisionDeliveryDTO d,
            QmsInspectionRevisionExecutionContract.Parsed p) {
        same(
                a.getId(),
                a.getTenantId(),
                a.getFactoryCode(),
                a.getTaskCode(),
                a.getProduceTaskCode(),
                a.getInspectionId(),
                a.getIntent(),
                a.getIntentFingerprint(),
                d,
                p);
        require(
                a.getBasisSnapshot() != null
                        && a.getBasisSnapshot().length() <= 65536
                        && hash(a.getBasisSnapshot()).equals(a.getBasisFingerprint())
                        && a.getReservedAt() != null,
                "准入原件摘要或时间损坏");
        var j = tree(a.getBasisSnapshot());
        require(
                j.path("schemaVersion").isInt()
                        && j.get("schemaVersion").intValue() == 1
                        && a.getId().toString().equals(j.path("requestId").textValue())
                        && a.getTenantId().toString().equals(j.path("tenantId").textValue())
                        && a.getFactoryCode().equals(j.path("factoryCode").textValue())
                        && a.getTaskCode().equals(j.path("taskCode").textValue())
                        && a.getProduceTaskCode().equals(j.path("produceTaskCode").textValue())
                        && a.getGenRecordId().toString().equals(j.path("genRecordId").textValue())
                        && a.getInspectionId().toString().equals(j.path("inspectionId").textValue())
                        && a.getBaseEventId().toString().equals(j.path("baseEventId").textValue())
                        && a.getBaseFingerprint().equals(j.path("baseFingerprint").textValue())
                        && j.path("baseSequence").isIntegralNumber()
                        && j.get("baseSequence").longValue() == a.getBaseSequence()
                        && j.path("baseStateVersion").isIntegralNumber()
                        && j.get("baseStateVersion").longValue() == a.getBaseStateVersion()
                        && a.getIntentFingerprint().equals(j.path("intentFingerprint").textValue())
                        && a.getReservedAt()
                        .toInstant()
                        .toString()
                        .equals(j.path("reservedAt").textValue()),
                "准入原件与实际冻结绑定不一致");
    }

    private void audit(
            MesInspectionRevisionCancelEntity c,
            MesInspectionRevisionDeliveryDTO d,
            QmsInspectionRevisionExecutionContract.Parsed p) {
        same(
                c.getId(),
                c.getTenantId(),
                c.getFactoryCode(),
                c.getTaskCode(),
                c.getProduceTaskCode(),
                c.getInspectionId(),
                c.getIntent(),
                c.getIntentFingerprint(),
                d,
                p);
        require(
                c.getCanceledBy() != null
                        && c.getCanceledBy() > 0
                        && c.getCanceledAt() != null
                        && c.getReason() != null
                        && !c.getReason().isBlank()
                        && c.getReason().length() <= 1000,
                "取消原件缺少完整操作依据");
    }

    private void same(
            Long id,
            Long tenant,
            String factory,
            String root,
            String process,
            Long inspection,
            String raw,
            String fp,
            MesInspectionRevisionDeliveryDTO d,
            QmsInspectionRevisionExecutionContract.Parsed p) {
        var q = p.request();
        require(
                Objects.equals(id, d.getRequestId())
                        && Objects.equals(tenant, d.getTenantId())
                        && Objects.equals(factory, d.getFactoryCode())
                        && Objects.equals(raw, d.getIntent())
                        && Objects.equals(fp, d.getIntentFingerprint())
                        && hash(raw).equals(fp)
                        && Objects.equals(root, q.path("source").path("orderNo").textValue())
                        && Objects.equals(
                        process, q.path("source").path("sourceTaskCode").textValue())
                        && Objects.equals(inspection.toString(), q.path("taskId").textValue()),
                "同号修订意图原文或绑定不一致，拒绝换件");
    }

    private MesInspectionRevisionAdmissionDTO receipt(
            MesInspectionRevisionDeliveryDTO d,
            QmsInspectionRevisionExecutionContract.Parsed p,
            MesInspectionRevisionAdmissionEntity a,
            MesInspectionRevisionCancelEntity c) {
        var q = p.request();
        boolean executed = admissions.executed(d.getRequestId(), tenant(), factory()) > 0;
        return new MesInspectionRevisionAdmissionDTO()
                .setContractVersion("MES_REVISION_ADMISSION_V1")
                .setRequestId(d.getRequestId())
                .setTenantId(tenant())
                .setFactoryCode(factory())
                .setTaskCode(q.path("source").path("orderNo").textValue())
                .setProduceTaskCode(q.path("source").path("sourceTaskCode").textValue())
                .setInspectionId(Long.valueOf(q.path("taskId").textValue()))
                .setGenRecordId(a == null ? null : a.getGenRecordId())
                .setBaseEventId(Long.valueOf(q.path("baseEventId").textValue()))
                .setBaseFingerprint(q.path("baseFingerprint").textValue())
                .setIntentFingerprint(d.getIntentFingerprint())
                .setStatus(
                        c != null
                                ? "CANCELED"
                                : executed ? "EXECUTED" : a != null ? "RESERVED" : "ABSENT")
                .setHeld(a != null && c == null && !executed)
                .setApplied(executed)
                .setReservedAt(a == null ? null : a.getReservedAt())
                .setAdmissionFingerprint(a == null ? null : a.getBasisFingerprint())
                .setCanceledAt(c == null ? null : c.getCanceledAt())
                .setCanceledBy(c == null ? null : c.getCanceledBy())
                .setCancelReason(c == null ? null : c.getReason());
    }
}
