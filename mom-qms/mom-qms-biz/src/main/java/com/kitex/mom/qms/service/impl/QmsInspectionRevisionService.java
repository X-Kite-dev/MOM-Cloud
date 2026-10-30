package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.jackson.MomJavaTimeModule;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 修订申请与独立审核原件。审核不改任务/事件、不投递执行许可，也不解除MES冲突。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionRevisionService {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new MomJavaTimeModule())
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> SOURCE_FIELDS =
            Set.of(
                    "sourceTaskCode",
                    "sourceRequestId",
                    "sourceRequestFingerprint",
                    "matCode",
                    "processCode",
                    "labelType",
                    "standardId",
                    "batchNo",
                    "num",
                    "inspectionNum",
                    "status",
                    "result",
                    "usableNum",
                    "handleNum",
                    "handleResult",
                    "orderNo",
                    "orderId",
                    "matBarcode",
                    "deptCode",
                    "latestInspectionTime",
                    "latestAppendTime");
    private final QmsInspectionRevisionMapper requests;
    private final QmsInspectionRevisionReviewMapper reviews;
    private final QmsInspectionEventMapper tasks;
    private final QmsInspectionEventService journal;
    private final QmsInspectionHistorySupport access;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Basis basis(Long taskId) {
        access.requireRead();
        installed();
        return current(taskId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public QmsInspectionRevisionEntity apply(QmsInspectionRevisionApply command) {
        access.requireWrite();
        installed();
        require(command != null, "缺少修订申请");
        var basis = current(command.taskId(), true);
        require(basis.available(), basis.unavailableReason());
        require(
                Objects.equals(basis.eventId(), Objects.toString(command.baseEventId(), null))
                        && Objects.equals(basis.fingerprint(), command.baseFingerprint()),
                "修订依据已变化，请重新核对当前原件");
        var result = command.result();
        int total = basis.source().path("num").intValue();
        require(
                Set.of("QUALIFIED", "UN_QUALIFIED").contains(Objects.toString(result, "")),
                "仅支持整批合格或不合格判定修订申请");
        require(!result.equals(basis.source().path("result").textValue()), "判定未变化，无需修订");
        int quantity = "QUALIFIED".equals(result) ? total : 0;
        require(
                Objects.equals(command.usable(), quantity)
                        && Objects.equals(command.handled(), quantity),
                "修订目标须明确整批可用与处置数量");
        String reason = note(command.reason(), "修订原因");
        require(requests.selectCount(scope(command.taskId())) < 1000, "单任务修订申请超过1000条，请先治理");
        require(
                requests.unresolved(tenant(), factory(), command.taskId()) == 0,
                "已有待审核或待执行修订，请先处理");
        var row =
                new QmsInspectionRevisionEntity()
                        .setId(IdWorker.getId())
                        .setTenantId(tenant())
                        .setFactoryCode(factory())
                        .setTaskId(command.taskId())
                        .setBaseEventId(command.baseEventId())
                        .setBaseSequence(basis.sequence())
                        .setBaseFingerprint(basis.fingerprint())
                        .setRequestedBy(actor())
                        .setRequestedAt(new Date())
                        .setTargetResult(result)
                        .setTargetUsable(quantity)
                        .setTargetHandled(quantity)
                        .setReason(reason);
        var body = new TreeMap<String, Object>();
        body.put("schemaVersion", 1);
        body.put("requestId", row.getId().toString());
        body.put("tenantId", tenant().toString());
        body.put("factoryCode", factory());
        body.put("taskId", row.getTaskId().toString());
        body.put("baseEventId", basis.eventId());
        body.put("baseSequence", basis.sequence());
        body.put("baseFingerprint", basis.fingerprint());
        body.put("requestedBy", row.getRequestedBy().toString());
        body.put("requestedAt", row.getRequestedAt().toInstant().toString());
        body.put("source", basis.source());
        body.put("targetResult", result);
        body.put("targetUsable", quantity);
        body.put("targetHandled", quantity);
        body.put("reason", reason);
        try {
            row.setPayload(JSON.writeValueAsString(body));
        } catch (Exception e) {
            throw new CheckedException("修订申请原件生成失败", e);
        }
        require(
                row.getPayload().getBytes(StandardCharsets.UTF_8).length <= 65536,
                "修订依据超过64KiB，禁止截断");
        row.setFingerprint(QmsInspectionEventDeliveryCodec.hash(row.getPayload()));
        require(requests.insert(row) == 1, "修订申请保存失败");
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public QmsInspectionRevisionReviewEntity review(
            Long taskId, Long requestId, QmsInspectionRevisionReview command) {
        access.requireWrite();
        installed();
        parent(taskId, true);
        var row = request(taskId, requestId);
        var frozen = verify(row);
        require(
                command != null
                        && Set.of("APPROVED", "REJECTED")
                                .contains(Objects.toString(command.decision(), "")),
                "审核意见无效");
        String note = note(command.note(), "审核依据");
        Long reviewer = actor();
        require(!reviewer.equals(row.getRequestedBy()), "申请人不能审核本人修订");
        require(decision(row) == null, "申请已审核，不能覆盖原意见");
        // 完整但过时的依据可驳回；损坏或缺失的原事件在所有入口均失败关闭。
        original(row, frozen, journal.originals("PROCEDURE", taskId));
        if ("APPROVED".equals(command.decision())) {
            var now = current(taskId, false);
            require(now.available(), now.unavailableReason());
            require(
                    now.eventId().equals(row.getBaseEventId().toString())
                            && now.sequence() == row.getBaseSequence()
                            && now.fingerprint().equals(row.getBaseFingerprint())
                            && now.source().equals(frozen.get("source")),
                    "申请依据已变化，不能通过旧版本审核");
        }
        var review =
                new QmsInspectionRevisionReviewEntity()
                        .setId(row.getId())
                        .setTenantId(tenant())
                        .setFactoryCode(factory())
                        .setTaskId(taskId)
                        .setRequestFingerprint(row.getFingerprint())
                        .setReviewedBy(reviewer)
                        .setReviewedAt(new Date())
                        .setDecision(command.decision())
                        .setNote(note);
        require(reviews.insert(review) == 1, "修订审核保存失败");
        return review;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(Long taskId, Long after, int limit) {
        access.requireRead();
        installed();
        parent(taskId, false);
        require(after != null && after >= 0 && limit > 0 && limit <= 50, "修订分页参数无效");
        var rows =
                requests.selectList(
                        scope(taskId)
                                .gt(QmsInspectionRevisionEntity::getId, after)
                                .orderByAsc(QmsInspectionRevisionEntity::getId)
                                .last("LIMIT " + (limit + 1)));
        var originals = journal.originals("PROCEDURE", taskId);
        boolean more = rows.size() > limit;
        var result = new ArrayList<Summary>();
        for (var row : rows.subList(0, Math.min(rows.size(), limit))) {
            var snapshot = verify(row);
            original(row, snapshot, originals);
            var d = decision(row);
            result.add(
                    new Summary(
                            row.getId().toString(),
                            row.getBaseEventId().toString(),
                            row.getBaseSequence(),
                            row.getTargetResult(),
                            row.getTargetUsable(),
                            row.getTargetHandled(),
                            row.getReason(),
                            row.getRequestedBy().toString(),
                            row.getRequestedAt(),
                            state(row, d),
                            d));
        }
        return new Page(
                "QMS_REVISION_REVIEW_V1",
                tenant().toString(),
                factory(),
                actor().toString(),
                taskId.toString(),
                result,
                more,
                result.isEmpty() ? after.toString() : result.get(result.size() - 1).requestId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(Long taskId, Long requestId) {
        access.requireRead();
        installed();
        parent(taskId, false);
        var r = request(taskId, requestId);
        var snapshot = verify(r);
        original(r, snapshot, journal.originals("PROCEDURE", taskId));
        var d = decision(r);
        return new Detail(
                "QMS_REVISION_REVIEW_V1",
                tenant().toString(),
                factory(),
                actor().toString(),
                taskId.toString(),
                r,
                d,
                state(r, d),
                snapshot);
    }

    /** 调用方已声明事务；冻结时只持本地父锁，不调用MES。 */
    String freezeExecution(Long taskId, Long requestId, Long executor, Date time) {
        // coordinateParent已完成实际工厂write授权；这里只作本地scope/事务及原件核验。
        require(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "冻结意图必须加入已授权准备事务");
        access.requireScope();
        parent(taskId, true);
        var r = request(taskId, requestId);
        var frozen = verify(r);
        var review = decision(r);
        require(review != null && "APPROVED".equals(review.getDecision()), "修订尚未独立审核通过");
        original(r, frozen, journal.originals("PROCEDURE", taskId));
        var now = current(taskId, false);
        require(
                now.available()
                        && Objects.equals(now.eventId(), r.getBaseEventId().toString())
                        && now.sequence() == r.getBaseSequence()
                        && Objects.equals(now.fingerprint(), r.getBaseFingerprint())
                        && now.source().equals(frozen.get("source")),
                "当前依据已变化，不能准备旧申请");
        require(
                r.getBaseSequence() == 2
                        && "FIRST".equals(now.source().path("labelType").textValue())
                        && "QUALIFIED".equals(now.source().path("result").textValue())
                        && "UN_QUALIFIED".equals(r.getTargetResult())
                        && "FINISHED".equals(now.source().path("status").textValue()),
                "首版执行准备限初次首检合格改不合格");
        return com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract.freeze(
                r, review, executor, time);
    }

    void coordinateParent(Long taskId) {
        access.requireWrite();
        installed();
        parent(taskId, true);
    }

    private void original(
            QmsInspectionRevisionEntity request,
            JsonNode snapshot,
            List<QmsInspectionEventEntity> chain) {
        require(!chain.isEmpty() && "CREATE".equals(chain.get(0).getEventType()), "修订依据缺少原始创建事件");
        var base =
                chain.stream()
                        .filter(e -> Objects.equals(e.getId(), request.getBaseEventId()))
                        .findFirst()
                        .orElse(null);
        require(
                base != null
                        && Objects.equals(base.getSequenceNo(), request.getBaseSequence())
                        && Objects.equals(base.getFingerprint(), request.getBaseFingerprint())
                        && "RESULT".equals(base.getEventType())
                        && QmsInspectionEventDeliveryCodec.eligible(base),
                "修订引用的原事件已缺失或不一致");
        var source = QmsInspectionEventDeliveryCodec.raw(base).get("source");
        var fields = new HashSet<String>();
        source.fieldNames().forEachRemaining(fields::add);
        require(
                fields.equals(SOURCE_FIELDS) && source.equals(snapshot.get("source")),
                "修订冻结来源与实际原事件不一致");
    }

    private Basis current(Long taskId, boolean lock) {
        var parent = parent(taskId, lock);
        var chain = journal.originals("PROCEDURE", taskId);
        if (chain.isEmpty() || !"CREATE".equals(chain.get(0).getEventType()))
            return unavailable(taskId, "缺少原始创建事件，不能申请修订");
        var head = chain.get(chain.size() - 1);
        if (!"RESULT".equals(head.getEventType())
                || !QmsInspectionEventDeliveryCodec.eligible(head))
            return unavailable(taskId, "仅支持普通MES来源的最新结果原件");
        var raw = QmsInspectionEventDeliveryCodec.raw(head);
        var source = raw.get("source");
        var fields = new HashSet<String>();
        source.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(SOURCE_FIELDS), "检验原件缺少完整可空来源字段或含未知字段");
        var actual = JSON.valueToTree(parent);
        var names = source.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode v = actual.has(name) ? actual.get(name) : JSON.getNodeFactory().nullNode();
            if ("standardId".equals(name) && v != null && !v.isNull())
                v = JSON.getNodeFactory().textNode(v.asText());
            if (!v.equals(source.get(name))) return unavailable(taskId, "当前任务与最新事件原件不一致，须先核对来源");
        }
        if (!(source.path("num").isIntegralNumber()
                && source.path("num").canConvertToInt()
                && source.path("num").intValue() > 0
                && Set.of("QUALIFIED", "UN_QUALIFIED").contains(source.path("result").asText())
                && Set.of("FINISHED", "UNHANDLED").contains(source.path("status").asText())))
            return unavailable(taskId, "当前判定尚不可申请受控修订");
        return new Basis(
                "QMS_REVISION_REVIEW_V1",
                tenant().toString(),
                factory(),
                actor().toString(),
                taskId.toString(),
                head.getId().toString(),
                head.getSequenceNo(),
                head.getFingerprint(),
                source.deepCopy(),
                true,
                null);
    }

    private Basis unavailable(Long taskId, String reason) {
        return new Basis(
                "QMS_REVISION_REVIEW_V1",
                tenant().toString(),
                factory(),
                actor().toString(),
                taskId.toString(),
                null,
                0,
                null,
                JSON.createObjectNode(),
                false,
                reason);
    }

    private Map<String, Object> parent(Long taskId, boolean lock) {
        require(taskId != null && taskId > 0, "检验任务ID无效");
        var p = tasks.procedureSnapshot(tenant(), factory(), taskId, lock);
        require(p != null, "当前组织不存在该工序检验任务");
        return p;
    }

    private QmsInspectionRevisionEntity request(Long task, Long id) {
        require(id != null && id > 0, "修订申请ID无效");
        var r = requests.selectOne(scope(task).eq(QmsInspectionRevisionEntity::getId, id));
        require(r != null, "当前任务不存在该修订申请");
        return r;
    }

    private JsonNode verify(QmsInspectionRevisionEntity r) {
        require(
                r.getPayload() != null
                        && r.getPayload().getBytes(StandardCharsets.UTF_8).length <= 65536
                        && Objects.equals(
                                QmsInspectionEventDeliveryCodec.hash(r.getPayload()),
                                r.getFingerprint()),
                "修订申请原件摘要不一致");
        try {
            var j = JSON.readTree(r.getPayload());
            require(
                    j != null
                            && j.isObject()
                            && j.path("schemaVersion").isInt()
                            && j.get("schemaVersion").intValue() == 1
                            && r.getId().toString().equals(j.path("requestId").textValue())
                            && r.getTenantId().toString().equals(j.path("tenantId").textValue())
                            && r.getFactoryCode().equals(j.path("factoryCode").textValue())
                            && r.getTaskId().toString().equals(j.path("taskId").textValue())
                            && r.getBaseEventId()
                                    .toString()
                                    .equals(j.path("baseEventId").textValue())
                            && j.path("baseSequence").isIntegralNumber()
                            && j.get("baseSequence").longValue() == r.getBaseSequence()
                            && r.getBaseFingerprint().equals(j.path("baseFingerprint").textValue())
                            && r.getRequestedBy()
                                    .toString()
                                    .equals(j.path("requestedBy").textValue())
                            && r.getRequestedAt()
                                    .toInstant()
                                    .toString()
                                    .equals(j.path("requestedAt").textValue())
                            && r.getReason().equals(j.path("reason").textValue())
                            && r.getTargetResult().equals(j.path("targetResult").textValue())
                            && j.path("targetUsable").isInt()
                            && j.get("targetUsable").intValue() == r.getTargetUsable()
                            && j.path("targetHandled").isInt()
                            && j.get("targetHandled").intValue() == r.getTargetHandled()
                            && j.path("source").isObject(),
                    "修订原件与索引身份不一致");
            return j;
        } catch (CheckedException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckedException("修订申请原件不可核验", e);
        }
    }

    private QmsInspectionRevisionReviewEntity decision(QmsInspectionRevisionEntity r) {
        var d =
                reviews.selectOne(
                        new LambdaQueryWrapper<QmsInspectionRevisionReviewEntity>()
                                .eq(QmsInspectionRevisionReviewEntity::getTenantId, tenant())
                                .eq(QmsInspectionRevisionReviewEntity::getFactoryCode, factory())
                                .eq(QmsInspectionRevisionReviewEntity::getTaskId, r.getTaskId())
                                .eq(QmsInspectionRevisionReviewEntity::getId, r.getId()));
        if (d != null)
            require(
                    Objects.equals(d.getRequestFingerprint(), r.getFingerprint())
                            && d.getReviewedBy() != null
                            && d.getReviewedBy() > 0
                            && !d.getReviewedBy().equals(r.getRequestedBy())
                            && d.getReviewedAt() != null
                            && !d.getReviewedAt().before(r.getRequestedAt())
                            && Set.of("APPROVED", "REJECTED")
                                    .contains(Objects.toString(d.getDecision(), "")),
                    "审核原件与申请不一致");
        return d;
    }

    private String state(QmsInspectionRevisionEntity request, QmsInspectionRevisionReviewEntity d) {
        if (d == null) return "PENDING_REVIEW";
        if (!"APPROVED".equals(d.getDecision())) return "REJECTED";
        String formal =
                requests.formalState(request.getId(), tenant(), factory(), request.getTaskId());
        if (formal != null) {
            require(!"CORRUPT".equals(formal), "正式修订终态原件缺失或损坏");
            return formal;
        }
        var canceled =
                requests.cancellationState(
                        request.getId(), tenant(), factory(), request.getTaskId());
        require(canceled != null && canceled >= 0, "取消回执或进度损坏，不能显示执行准备终态");
        return canceled == 1 ? "CANCELED_PREPARATION" : "PENDING_EXECUTION";
    }

    private LambdaQueryWrapper<QmsInspectionRevisionEntity> scope(Long task) {
        return new LambdaQueryWrapper<QmsInspectionRevisionEntity>()
                .eq(QmsInspectionRevisionEntity::getTenantId, tenant())
                .eq(QmsInspectionRevisionEntity::getFactoryCode, factory())
                .eq(QmsInspectionRevisionEntity::getTaskId, task);
    }

    private void installed() {
        Integer v;
        try {
            v = requests.ready();
        } catch (RuntimeException e) {
            throw new CheckedException("请先安装并核验受控修订结构362及协调结构364", e);
        }
        require(Objects.equals(v, 1), "受控修订结构或永久守卫未就绪");
    }

    private static String note(String value, String label) {
        require(
                value != null && !value.isBlank() && value.strip().length() <= 1000,
                label + "须为1至1000个字符");
        return value.strip();
    }

    private static Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private static String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private static Long actor() {
        return SecurityUtils.getUser().getId();
    }

    public record Basis(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String taskId,
            String eventId,
            long sequence,
            String fingerprint,
            JsonNode source,
            boolean available,
            String unavailableReason) {}

    public record Summary(
            String requestId,
            String baseEventId,
            long baseSequence,
            String targetResult,
            Integer targetUsable,
            Integer targetHandled,
            String reason,
            String requestedBy,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
                    Date requestedAt,
            String state,
            QmsInspectionRevisionReviewEntity review) {}

    public record Page(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String taskId,
            List<Summary> items,
            boolean hasMore,
            String nextCursor) {}

    public record Detail(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String taskId,
            QmsInspectionRevisionEntity request,
            QmsInspectionRevisionReviewEntity review,
            String state,
            JsonNode snapshot) {}
}
