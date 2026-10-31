package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesInspectionRevisionAdmissionDTO;
import com.kitex.mom.mes.dto.MesInspectionRevisionDeliveryDTO;
import com.kitex.mom.mes.dto.MesInspectionRevisionExecutionDTO;
import com.kitex.mom.mes.dto.MesInspectionRevisionExecutionReceiptDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.MesInspectionEventStateMapper;
import com.kitex.mom.mes.mapper.MesInspectionRevisionAdmissionMapper;
import com.kitex.mom.mes.mapper.MesInspectionRevisionCancelMapper;
import com.kitex.mom.mes.mapper.MesInspectionRevisionExecutionMapper;
import com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract.*;

/**
 * 正式封存/应用/中止；全部事实与标准应用账共享根互斥及本地事务。
 */
@Service
@RequiredArgsConstructor
public class MesInspectionRevisionExecutionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MesInspectionRevisionAdmissionService preparation;
    private final MesInspectionRevisionAdmissionMapper admissions;
    private final MesInspectionRevisionCancelMapper cancels;
    private final MesInspectionRevisionExecutionMapper ledger;
    private final MesProductionTaskMutex mutex;
    private final MesInspectionEventStateMapper states;
    private final MesInspectionEventInboxService inbox;
    private final MesInspectionEventProjection projection;
    private final MesQualityGateSourceEvidence sources;
    private final MesInspectionEventApplicationWorker applications;
    @Value("${mom.qms-inspection-revision.delivery-secret:}")
    private String deliverySecret;

    private static JsonNode tree(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception ex) {
            throw new CheckedException("正式修订依据不可解析", ex);
        }
    }

    private static void zero(Object n) {
        require(n instanceof Number && new BigDecimal(n.toString()).signum() == 0, "产出计数非明确零");
    }

    private static long number(Map<String, Object> m, String k) {
        require(m != null && m.get(k) instanceof Number, "缺少可核计数");
        return ((Number) m.get(k)).longValue();
    }

    private static Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private static String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private static void require(boolean b, String m) {
        if (!b) throw new CheckedException(m);
    }

    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public MesInspectionRevisionExecutionReceiptDTO receive(MesInspectionRevisionExecutionDTO d) {
        var p = authenticate(d);
        var q = p.request();
        String root = q.path("source").path("orderNo").textValue();
        mutex.lock(root);
        require(Integer.valueOf(1).equals(ledger.ready()), "请先安装正式修订结构365");
        var resolved = ledger.resolution(d.getRequestId(), tenant(), factory());
        if (resolved != null) {
            if ("SEAL".equals(d.getOperation())) return original(resolved, d);
            require(
                    resolved.getOperation().equals(d.getOperation())
                            && resolved.getPayload().equals(d.getOutcome()),
                    "同号正式修订已有不同终态");
            return original(resolved, d);
        }
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
        var seal = ledger.seal(d.getRequestId(), tenant(), factory());
        if (a != null)
            preparation.audit(
                    a,
                    new MesInspectionRevisionDeliveryDTO()
                            .setRequestId(d.getRequestId())
                            .setTenantId(tenant())
                            .setFactoryCode(factory())
                            .setIntent(d.getIntent())
                            .setIntentFingerprint(d.getIntentFingerprint()),
                    p);
        if (seal != null) auditSeal(seal, a, d);
        if ("ABORT".equals(d.getOperation())) {
            var proof = parse(d.getOutcome(), d.getIntent());
            require(
                    "ABORTED".equals(proof.decision())
                            && (proof.sealFingerprint() == null
                            || seal != null
                            && proof.sealFingerprint()
                            .equals(seal.getFingerprint())),
                    "中止原件与封存不一致");
            Date now = new Date();
            if (seal != null) require(!now.before(seal.getSealedAt()), "本机时钟早于封存时间");
            if (c != null)
                require(
                        c.getIntent().equals(d.getIntent())
                                && Objects.equals(c.getCanceledBy(), proof.abortBy())
                                && c.getReason().equals(proof.reason()),
                        "现有取消原件与正式中止不一致");
            var receipt =
                    base(d, p, seal)
                            .setStatus("ABORTED")
                            .setHeld(false)
                            .setApplied(false)
                            .setOutcomeFingerprint(d.getOutcomeFingerprint())
                            .setResolvedAt(now);
            var cancel =
                    c != null
                            ? c
                            : new MesInspectionRevisionCancelEntity()
                            .setId(d.getRequestId())
                            .setTenantId(tenant())
                            .setFactoryCode(factory())
                            .setTaskCode(root)
                            .setProduceTaskCode(
                                    q.path("source").path("sourceTaskCode").textValue())
                            .setInspectionId(Long.valueOf(q.path("taskId").textValue()))
                            .setIntent(d.getIntent())
                            .setIntentFingerprint(d.getIntentFingerprint())
                            .setCanceledBy(proof.abortBy())
                            .setCanceledAt(now)
                            .setReason(proof.reason());
            receipt.setCancellation(
                    new MesInspectionRevisionAdmissionDTO()
                            .setContractVersion("MES_REVISION_ADMISSION_V1")
                            .setRequestId(d.getRequestId())
                            .setTenantId(tenant())
                            .setFactoryCode(factory())
                            .setTaskCode(root)
                            .setProduceTaskCode(cancel.getProduceTaskCode())
                            .setInspectionId(cancel.getInspectionId())
                            .setGenRecordId(a == null ? null : a.getGenRecordId())
                            .setBaseEventId(Long.valueOf(q.path("baseEventId").textValue()))
                            .setBaseFingerprint(q.path("baseFingerprint").textValue())
                            .setIntentFingerprint(d.getIntentFingerprint())
                            .setStatus("CANCELED")
                            .setHeld(false)
                            .setApplied(false)
                            .setReservedAt(a == null ? null : a.getReservedAt())
                            .setAdmissionFingerprint(a == null ? null : a.getBasisFingerprint())
                            .setCanceledAt(cancel.getCanceledAt())
                            .setCanceledBy(cancel.getCanceledBy())
                            .setCancelReason(cancel.getReason()));
            append(d, p, seal, receipt, null);
            if (c == null) require(cancels.insert(cancel) == 1, "永久中止取消墓碑保存失败");
            return receipt;
        }
        require(a != null && c == null, "正式修订缺少有效准入或已取消");
        var context = checked(a, p, d.getOperation().equals("APPLY") ? d.getOutcome() : null);
        if ("SEAL".equals(d.getOperation())) {
            if (seal != null)
                return base(d, p, seal).setStatus("SEALED").setHeld(true).setApplied(false);
            Date now = new Date();
            require(!now.before(a.getReservedAt()), "当前时钟早于准入");
            var body = new TreeMap<String, Object>();
            body.put("schemaVersion", 1);
            body.put("requestId", a.getId().toString());
            body.put("intentFingerprint", a.getIntentFingerprint());
            body.put("admissionFingerprint", a.getBasisFingerprint());
            body.put("genRecordId", a.getGenRecordId().toString());
            body.put("baseEventId", a.getBaseEventId().toString());
            body.put("baseFingerprint", a.getBaseFingerprint());
            body.put("sealedAt", now.toInstant().toString());
            String raw = write(body);
            seal =
                    new MesInspectionRevisionSealEntity()
                            .setId(a.getId())
                            .setTenantId(tenant())
                            .setFactoryCode(factory())
                            .setTaskCode(a.getTaskCode())
                            .setProduceTaskCode(a.getProduceTaskCode())
                            .setInspectionId(a.getInspectionId())
                            .setGenRecordId(a.getGenRecordId())
                            .setIntentFingerprint(a.getIntentFingerprint())
                            .setAdmissionFingerprint(a.getBasisFingerprint())
                            .setPayload(raw)
                            .setFingerprint(hash(raw))
                            .setSealedAt(now);
            require(ledger.insert(seal) == 1, "封存原件保存失败");
            return base(d, p, seal).setStatus("SEALED").setHeld(true).setApplied(false);
        }
        require(seal != null, "正式应用缺少封存");
        var proof = parse(d.getOutcome(), d.getIntent());
        require(
                "COMMITTED".equals(proof.decision())
                        && seal.getFingerprint().equals(proof.sealFingerprint()),
                "正式提交原件与封存不一致");
        inbox.acceptRevision(proof.eventPayload(), hash(proof.eventPayload()));
        Date now = new Date();
        require(!now.before(seal.getSealedAt()), "当前时钟早于封存");
        Long event = Long.valueOf(proof.event().path("eventId").textValue());
        var receipt =
                base(d, p, seal)
                        .setStatus("APPLIED")
                        .setHeld(false)
                        .setApplied(true)
                        .setEventId(event)
                        .setEventFingerprint(hash(proof.eventPayload()))
                        .setOutcomeFingerprint(d.getOutcomeFingerprint())
                        .setResolvedAt(now);
        append(d, p, seal, receipt, event);
        require(applications.applyRevision(context.getId(), event, now), "正式修订未应用，已整体回滚");
        return receipt;
    }

    private MesInspectionGenRecordEntity checked(
            MesInspectionRevisionAdmissionEntity a,
            QmsInspectionRevisionExecutionContract.Parsed p,
            String outcome) {
        var process = sources.parent(a.getProduceTaskCode());
        var root = sources.root(process);
        var basis = tree(a.getBasisSnapshot());
        require(
                root.getCode().equals(a.getTaskCode())
                        && root.getId().toString().equals(basis.path("rootId").textValue())
                        && process.getId().toString().equals(basis.path("processId").textValue()),
                "正式修订原根/工序已变化");
        require(
                states.lockRoot(tenant(), factory(), root.getCode()).equals(List.of(root.getId()))
                        && states.lockProcess(tenant(), factory(), process.getCode())
                        .equals(List.of(process.getId())),
                "正式修订父行身份变化");
        var records = states.lockGenerations(tenant(), factory(), process.getCode());
        require(records.size() <= 5000, "检验生成记录超限");
        var g =
                records.stream()
                        .filter(v -> Objects.equals(v.getId(), a.getGenRecordId()))
                        .findFirst()
                        .orElseThrow(() -> new CheckedException("缺少原检验生成绑定"));
        require(
                Boolean.TRUE.equals(g.getQualityEventManaged())
                        && Objects.equals(g.getInspectionId(), a.getInspectionId()),
                "检验绑定已变化");
        var state = projection.binding(g, true);
        require(
                "ACTIVE".equals(state.getStatus())
                        && state.getLastSequence() == 2
                        && Objects.equals(state.getLastEventId(), a.getBaseEventId())
                        && Objects.equals(state.getLastFingerprint(), a.getBaseFingerprint())
                        && Objects.equals(state.getVersion(), a.getBaseStateVersion()),
                "修订前事件游标已变化或冲突");
        var chain = inbox.localChain(g);
        require(chain.size() == 2 || outcome != null && chain.size() == 3, "修订前存在未决后件");
        require(
                chain.get(0).getSequenceNo() == 1
                        && "CREATE".equals(chain.get(0).getEventType())
                        && Objects.equals(chain.get(1).getId(), a.getBaseEventId())
                        && chain.get(1).getFingerprint().equals(a.getBaseFingerprint())
                        && tree(chain.get(1).getPayload())
                        .get("source")
                        .equals(p.request().get("source")),
                "原首检事件/来源不一致");
        if (chain.size() == 3) {
            var proof = parse(outcome, a.getIntent());
            require(chain.get(2).getPayload().equals(proof.eventPayload()), "第三收件不是同一修订原文");
        }
        for (int i = 0; i < 2; i++) {
            var app = states.application(chain.get(i).getId(), tenant(), factory());
            require(
                    app != null
                            && app.getDecisionSnapshot() != null
                            && hash(app.getDecisionSnapshot()).equals(app.getDecisionFingerprint())
                            && basis.path("applicationFingerprints")
                            .path(i)
                            .asText()
                            .equals(app.getDecisionFingerprint()),
                    "修订前正式应用原件已变化");
            projection.applicationOriginal(g, app);
        }
        sources.verify(g, inbox.source(chain.get(1)));
        var rootFacts = admissions.rootFacts(tenant(), factory(), root.getCode());
        require(
                rootFacts != null
                        && Set.of("DISPATCH", "PRODUCT")
                        .contains(Objects.toString(rootFacts.get("status"), ""))
                        && !"REWORK".equals(rootFacts.get("resourceType")),
                "生产根不在可修订状态");
        zero(rootFacts.get("complete"));
        require(
                admissions.reports(tenant(), factory(), root.getCode()) == 0
                        && admissions.proofs(tenant(), factory(), root.getCode()) == 0
                        && ledger.stockRows(tenant(), factory(), root.getCode()) == 0,
                "修订前已有报工/证明/库存");
        var procs = admissions.processFacts(tenant(), factory(), root.getCode());
        var arrangements = admissions.arrangementFacts(tenant(), factory(), root.getCode());
        require(
                number(procs, "total") > 0
                        && number(procs, "total") <= 5000
                        && number(arrangements, "total") <= 5000
                        && number(procs, "invalid") == 0
                        && number(arrangements, "invalid") == 0
                        && Boolean.TRUE.equals(admissions.timingReady())
                        && admissions.activeTiming(tenant(), factory(), root.getCode()) == 0,
                "修订前计数不为零或仍在生产计时");
        return g;
    }

    private void auditSeal(
            MesInspectionRevisionSealEntity s,
            MesInspectionRevisionAdmissionEntity a,
            MesInspectionRevisionExecutionDTO d) {
        require(
                a != null
                        && s.getIntentFingerprint().equals(d.getIntentFingerprint())
                        && s.getAdmissionFingerprint().equals(a.getBasisFingerprint())
                        && s.getGenRecordId().equals(a.getGenRecordId())
                        && s.getPayload() != null
                        && hash(s.getPayload()).equals(s.getFingerprint())
                        && s.getSealedAt() != null
                        && tree(s.getPayload())
                        .path("sealedAt")
                        .textValue()
                        .equals(s.getSealedAt().toInstant().toString()),
                "封存原件或绑定损坏");
    }

    private void append(
            MesInspectionRevisionExecutionDTO d,
            QmsInspectionRevisionExecutionContract.Parsed p,
            MesInspectionRevisionSealEntity s,
            MesInspectionRevisionExecutionReceiptDTO receipt,
            Long event) {
        String raw = write(receipt);
        require(
                ledger.resolve(
                        new MesInspectionRevisionResolutionEntity()
                                .setId(d.getRequestId())
                                .setTenantId(tenant())
                                .setFactoryCode(factory())
                                .setTaskCode(receipt.getTaskCode())
                                .setProduceTaskCode(receipt.getProduceTaskCode())
                                .setInspectionId(receipt.getInspectionId())
                                .setGenRecordId(receipt.getGenRecordId())
                                .setOperation(d.getOperation())
                                .setIntentFingerprint(d.getIntentFingerprint())
                                .setSealFingerprint(s == null ? null : s.getFingerprint())
                                .setEventId(event)
                                .setEventFingerprint(receipt.getEventFingerprint())
                                .setPayload(d.getOutcome())
                                .setFingerprint(d.getOutcomeFingerprint())
                                .setReceipt(raw)
                                .setReceiptFingerprint(hash(raw))
                                .setResolvedAt(receipt.getResolvedAt()))
                        == 1,
                "正式修订终态未保存");
    }

    private MesInspectionRevisionExecutionReceiptDTO original(
            MesInspectionRevisionResolutionEntity r, MesInspectionRevisionExecutionDTO d) {
        require(
                r.getIntentFingerprint().equals(d.getIntentFingerprint())
                        && hash(r.getPayload()).equals(r.getFingerprint())
                        && hash(r.getReceipt()).equals(r.getReceiptFingerprint()),
                "正式修订永久终态损坏");
        parse(r.getPayload(), d.getIntent());
        try {
            var value =
                    JSON.readValue(r.getReceipt(), MesInspectionRevisionExecutionReceiptDTO.class);
            require(
                    value.getRequestId().equals(d.getRequestId())
                            && value.getTenantId().equals(tenant())
                            && value.getFactoryCode().equals(factory())
                            && value.getOutcomeFingerprint().equals(r.getFingerprint()),
                    "永久回执身份不一致");
            return value;
        } catch (Exception ex) {
            throw new CheckedException("正式修订回执不可核验", ex);
        }
    }

    private MesInspectionRevisionExecutionReceiptDTO base(
            MesInspectionRevisionExecutionDTO d,
            QmsInspectionRevisionExecutionContract.Parsed p,
            MesInspectionRevisionSealEntity s) {
        var q = p.request();
        return new MesInspectionRevisionExecutionReceiptDTO()
                .setContractVersion("MES_REVISION_EXECUTION_V1")
                .setRequestId(d.getRequestId())
                .setTenantId(tenant())
                .setFactoryCode(factory())
                .setTaskCode(q.path("source").path("orderNo").textValue())
                .setProduceTaskCode(q.path("source").path("sourceTaskCode").textValue())
                .setInspectionId(Long.valueOf(q.path("taskId").textValue()))
                .setGenRecordId(s == null ? null : s.getGenRecordId())
                .setIntentFingerprint(d.getIntentFingerprint())
                .setSealFingerprint(s == null ? null : s.getFingerprint())
                .setSealedAt(s == null ? null : s.getSealedAt());
    }

    private QmsInspectionRevisionExecutionContract.Parsed authenticate(
            MesInspectionRevisionExecutionDTO d) {
        MesQualityGateSourceEvidence.scope();
        require(
                d != null
                        && Set.of("SEAL", "APPLY", "ABORT")
                        .contains(Objects.toString(d.getOperation(), ""))
                        && Objects.equals(d.getTenantId(), tenant())
                        && Objects.equals(d.getFactoryCode(), factory())
                        && d.getRequestId() != null
                        && d.getRequestId() > 0
                        && d.getIssuedAt() != null
                        && d.getIssuedAt() >= Instant.now().getEpochSecond() - 90
                        && d.getIssuedAt() <= Instant.now().getEpochSecond() + 15
                        && d.getNonce() != null
                        && d.getNonce().matches("[A-Za-z0-9-]{16,64}")
                        && d.signatureMatches(deliverySecret),
                "正式修订签名/时窗/组织不一致");
        require(
                d.getIntent() != null && hash(d.getIntent()).equals(d.getIntentFingerprint()),
                "正式修订意图指纹无效");
        var p = QmsInspectionRevisionExecutionContract.parse(d.getIntent());
        require(
                d.getRequestId().toString().equals(p.request().path("requestId").textValue())
                        && tenant().toString().equals(p.request().path("tenantId").textValue())
                        && factory().equals(p.request().path("factoryCode").textValue()),
                "正式修订意图身份不一致");
        require(
                "SEAL".equals(d.getOperation())
                        ? d.getOutcome() == null && d.getOutcomeFingerprint() == null
                        : d.getOutcome() != null
                        && hash(d.getOutcome()).equals(d.getOutcomeFingerprint()),
                "正式结果原文/SHA不一致");
        return p;
    }
}
