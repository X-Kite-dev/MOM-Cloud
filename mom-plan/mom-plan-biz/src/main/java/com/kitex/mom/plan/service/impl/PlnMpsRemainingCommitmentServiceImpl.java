package com.kitex.mom.plan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.PlnMpsCapacityDTO;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.exception.PlnMpsRemainingRejectedException;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnMpsRemainingCommitmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.plan.service.impl.PlnMpsRemainingSnapshotSupport.*;

/**
 * 已开工来源剩余责任状态机：不可变编制、编制人取消、异岗发布与精确前版替代。
 *
 * <p>请求号→租户/工厂发布锁→原桶→案件头→案件行。正常新增生产允许推进，
 * 原基线纠正/来源变化使案件失效；远端缺口不伪造已失效或已发布。
 * 原MPS/root/MES/库存/质量不在本服务写集合内。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnMpsRemainingCommitmentServiceImpl
        extends ServiceImpl<PlnMpsRemainingCommitmentMapper, PlnMpsRemainingCommitmentEntity>
        implements PlnMpsRemainingCommitmentService {
    private static final Set<String> CHECKS = Set.of("SOURCE_BASELINE_VALID", "QUANTITY_CONSERVED",
            "CAPACITY_RECHECKED", "CUSTOMER_DUE_PRESERVED");
    private final PlnMpsRemainingCommitmentEvidenceReader evidenceReader;
    private final PlnMpsExecutionSourceReader sourceReader;
    private final PlnMpsRemainingFulfillmentCalculator calculator;
    private final PlnMpsRccpService capacityService;
    private final PlnMpsService mpsService;
    private final PlnMpsPolicyMapper requestLockMapper;
    private final PlnMpsCapacityPublicationLock publicationLock;
    private final PlnMpsLineMapper originalMapper;
    private final PlnMpsRemainingCommitmentHeadMapper headMapper;
    private final PlnMpsRemainingCommitmentLineMapper scheduleMapper;
    private final PlnMpsRemainingCommitmentOperationMapper operationMapper;

    @Override
    public PlnMpsRemainingCommitmentDTO.Preview preview(PlnMpsRemainingCommitmentDTO.PreviewCommand command) {
        authorize("read");
        return prepare(command, false).preview();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsRemainingCommitmentDTO.Detail submit(PlnMpsRemainingCommitmentDTO.Submit command) {
        authorize("write");
        require(command != null && command.getLineId() != null, "编制须指定精确原MPS桶");
        String request = text(command.getRequestId(), 100, "永久编制号");
        String reason = text(command.getReason(), 2000, "编制原因");
        String intent = text(command.getIntentFingerprint(), 64, "预览结构意图指纹");
        var normalized = normalized(command.getSchedules());
        String payload = commandSnapshot(Arrays.asList(idText(command.getObservationId()), idText(command.getLineId()),
                idText(command.getPreviousCommitmentId()), normalized, intent, reason));
        String fingerprint = fingerprint("SUBMIT", payload);
        lockRequest(request);
        var replay = replay(request, fingerprint);
        if (replay != null) return replay;
        publicationLock.acquire(factory());
        var locked = lockOriginalAndHead(command.getLineId());
        Prepared prepared;
        try {
            require(Objects.equals(locked.head().getCurrentCommitmentId(), command.getPreviousCommitmentId()),
                    "原桶当前生效版本已变化，须刷新并显式指定前版");
            prepared = prepare(command, true);
            require(Objects.equals(intent, prepared.preview().getIntentFingerprint()), "基线/策略/分期/前版意图已变化，请重新预览");
        } catch (CheckedException exception) {
            throw new PlnMpsRemainingRejectedException(request, "SUBMIT", command.getLineId().toString(), payload, exception.getMessage());
        }
        var basis = prepared.reference().baseline();
        String baselineJson = snapshot(basis);
        String policyJson = policySnapshot(prepared.preview().getPolicy());
        String capacityJson = snapshot(Arrays.asList(prepared.evaluation(), prepared.preview().getCapacity(), prepared.candidates()));
        var previous = baseMapper.selectList(scope().eq(PlnMpsRemainingCommitmentEntity::getMpsLineId, command.getLineId())
                .orderByDesc(PlnMpsRemainingCommitmentEntity::getCommitmentVersion).last("LIMIT 1"));
        int version = previous.isEmpty() ? 1 : Math.addExact(previous.get(0).getCommitmentVersion(), 1);
        var record = new PlnMpsRemainingCommitmentEntity().setCommitmentNo("MPSR-" + IdUtil.getSnowflakeNextIdStr())
                .setMpsLineId(command.getLineId()).setMpsVersionId(locked.original().getVersionId())
                .setRootIssueId(Long.valueOf(basis.getRootIssueId())).setRootIssueNo(basis.getRootIssueNo())
                .setCommitmentVersion(version).setPreviousCommitmentId(command.getPreviousCommitmentId())
                .setObservationId(command.getObservationId()).setToleranceMinutes(prepared.reference().observation().getToleranceMinutes())
                .setRequestId(request).setRequestFingerprint(fingerprint).setProductCode(basis.getProductCode())
                .setProductUnit(basis.getProductUnit()).setCapacityResourceCode(basis.getCapacityResourceCode())
                .setOriginalQuantity(quantity(basis.getOriginalQuantity())).setBaselineQuantity(quantity(basis.getBaselineQuantity()))
                .setRemainingQuantity(quantity(basis.getRemainingQuantity())).setOriginalPlannedDate(LocalDate.parse(basis.getPlannedDate()))
                .setCustomerDueDate(LocalDate.parse(basis.getSourceDueDate())).setBaselineSnapshot(baselineJson)
                .setBaselineFingerprint(hash(baselineJson)).setPolicyId(prepared.preview().getPolicy().getId())
                .setPolicyVersion(prepared.preview().getPolicy().getPolicyVersion()).setPolicySnapshot(policyJson)
                .setPolicyFingerprint(hash(policyJson)).setCapacitySnapshot(capacityJson).setCapacityFingerprint(hash(capacityJson))
                .setStatus("PENDING").setApplicantCode(operator()).setSubmittedTime(LocalDateTime.now()).setReason(reason).setFactoryCode(factory());
        record.setTenantId(TenantContextHolder.getTenantId());
        record.setCreateBy(operator());
        record.setCreateTime(LocalDateTime.now());
        require(save(record), "剩余责任版本落账失败");
        for (var schedule : normalized) {
            var capacity = prepared.candidates().stream().filter(row -> Objects.equals(row.getBucketNo(), schedule.getBucketNo())).findFirst().orElse(null);
            String lineEvidence = snapshot(capacity == null ? Arrays.asList("CURRENT_FULFILLED", schedule) : capacity);
            var row = new PlnMpsRemainingCommitmentLineEntity().setCommitmentId(record.getId()).setMpsLineId(record.getMpsLineId())
                    .setBucketNo(schedule.getBucketNo()).setPromisedDate(LocalDate.parse(schedule.getPromisedDate()))
                    .setQuantity(quantity(schedule.getQuantity())).setProductUnit(record.getProductUnit())
                    .setCapacityResourceCode(record.getCapacityResourceCode())
                    .setProjectedMinutes(capacity == null ? BigDecimal.ZERO : capacity.getCapacityDemandMinutes())
                    .setCapacitySnapshot(lineEvidence).setCapacityFingerprint(hash(lineEvidence)).setFactoryCode(factory());
            row.setTenantId(TenantContextHolder.getTenantId());
            row.setCreateBy(operator());
            row.setCreateTime(LocalDateTime.now());
            require(scheduleMapper.insert(row) == 1, "剩余责任完整日桶保存失败");
        }
        var detail = detailInternal(record);
        audit(record, request, fingerprint, "SUBMIT", null, "PENDING", payload, detail);
        return detail;
    }

    @Override
    public PlnMpsRemainingCommitmentDTO.Detail detail(Long id) {
        authorize("read");
        return detailInternal(record(id, false));
    }

    @Override
    public Page<PlnMpsRemainingCommitmentEntity> history(Long lineId, long current, long size) {
        authorize("read");
        require(lineId != null && current > 0 && size > 0 && size <= 100, "精确原桶必填，每页最多100版");
        original(lineId, false);
        return page(new Page<>(current, size), scope().eq(PlnMpsRemainingCommitmentEntity::getMpsLineId, lineId)
                .select(PlnMpsRemainingCommitmentEntity.class, field -> !field.getProperty().endsWith("Snapshot"))
                .orderByDesc(PlnMpsRemainingCommitmentEntity::getCommitmentVersion));
    }

    @Override
    public PlnMpsRemainingCommitmentOperationEntity byRequest(String requestId) {
        authorize("read");
        return operation(text(requestId, 100, "永久命令号"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsRemainingCommitmentDTO.Detail cancel(PlnMpsRemainingCommitmentDTO.Cancel command) {
        authorize("write");
        require(command != null && command.getId() != null, "待审剩余版本必填");
        String request = text(command.getRequestId(), 100, "永久取消号");
        String reason = text(command.getReason(), 2000, "取消原因");
        String payload = commandSnapshot(Arrays.asList(idText(command.getId()), reason));
        String fingerprint = fingerprint("CANCEL", payload);
        lockRequest(request);
        var replay = replay(request, fingerprint);
        if (replay != null) return replay;
        var located = record(command.getId(), false);
        publicationLock.acquire(factory());
        lockOriginalAndHead(located.getMpsLineId());
        var record = record(command.getId(), true);
        if (!"PENDING".equals(record.getStatus()) || !Objects.equals(record.getApplicantCode(), operator())) {
            throw new PlnMpsRemainingRejectedException(request, "CANCEL", command.getId().toString(), payload,
                    "只有原编制人可取消待审版本；已发布不能取消或恢复旧版");
        }
        record.setStatus("CANCELLED").setCancelRequestId(request).setCancelRequestFingerprint(fingerprint)
                .setCancelledBy(operator()).setCancelledTime(LocalDateTime.now()).setCancelReason(reason);
        require(updateById(record), "取消回执保存失败");
        var detail = detailInternal(record);
        audit(record, request, fingerprint, "CANCEL", "PENDING", "CANCELLED", payload, detail);
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsRemainingCommitmentDTO.Detail review(PlnMpsRemainingCommitmentDTO.Review command) {
        authorize("write");
        require(command != null && command.getId() != null, "待审剩余版本必填");
        String request = text(command.getRequestId(), 100, "永久审核号");
        String reason = text(command.getReason(), 2000, "审核意见");
        require(Set.of("PUBLISH", "REJECT").contains(StrUtil.blankToDefault(command.getDecision(), "")), "审核动作只能发布或驳回");
        var checks = command.getChecks() == null ? List.<String>of() : command.getChecks();
        require(checks.stream().allMatch(Objects::nonNull) && CHECKS.containsAll(checks), "固定核验目录无效");
        require(checks.stream().distinct().count() == checks.size(), "核验目录不得重复");
        String risk = StrUtil.trimToEmpty(command.getRiskAcknowledgement());
        require(risk.length() <= 2000, "风险/模式说明最多2000字");
        String payload = commandSnapshot(Arrays.asList(idText(command.getId()), command.getDecision(), checks.stream().sorted().toList(), reason, risk));
        String fingerprint = fingerprint("REVIEW", payload);
        lockRequest(request);
        var replay = replay(request, fingerprint);
        if (replay != null) return replay;
        var located = record(command.getId(), false);
        publicationLock.acquire(factory());
        var locked = lockOriginalAndHead(located.getMpsLineId());
        var record = record(command.getId(), true);
        if (!"PENDING".equals(record.getStatus()) || Objects.equals(record.getApplicantCode(), operator())) {
            throw new PlnMpsRemainingRejectedException(request, "REVIEW", command.getId().toString(), payload,
                    "只允许另一实名岗位审核待审版本，编制人与审核人必须分离");
        }
        String after = "REJECTED";
        String explanation = reason;
        String capacityJson = null;
        String staleReason = null;
        try {
            if ("PUBLISH".equals(command.getDecision())) {
                require(new HashSet<>(checks).equals(CHECKS), "发布须完成来源基线、数量守恒、能力重核及客户交期四项核验");
                var policy = policy();
                require("BLOCK".equals(policy.getRccpMode()) || !risk.isEmpty(), "WARN/OFF发布须明确填写风险/模式说明，不冒充能力已验证");
                String stale = null;
                if (!Objects.equals(locked.head().getCurrentCommitmentId(), record.getPreviousCommitmentId()))
                    stale = "当前前版已被另一发布替代";
                if (!Objects.equals(hash(record.getPolicySnapshot()), record.getPolicyFingerprint()))
                    throw new CheckedException("原冻结策略正文损坏，禁止发布");
                if (!Objects.equals(hash(policySnapshot(policy)), record.getPolicyFingerprint()))
                    stale = "工厂策略已换版，须重新编制";
                var baseline = baseline(record);
                var schedules = schedules(lines(record.getId()));
                var current = sourceReader.readBuckets(List.of(record.getMpsLineId()), record.getToleranceMinutes()).get(0);
                var evaluation = calculator.evaluate(baseline, current, schedules, LocalDateTime.now(), record.getToleranceMinutes());
                if (Set.of("BASELINE_STALE", "SOURCE_STALE", "BACKFILLED_BASELINE").contains(evaluation.getResult())) {
                    stale = String.join("；", evaluation.getGaps());
                } else {
                    require(evaluation.getGaps().isEmpty(), "当前执行依据待核，保留待审版本：" + String.join("；", evaluation.getGaps()));
                }
                try {
                    dates(schedules, baseline, policy);
                } catch (CheckedException exception) {
                    stale = exception.getMessage();
                }
                if (stale == null) {
                    var candidates = candidates(locked.original(), evaluation);
                    var capacity = assess(policy, candidates, record.getMpsLineId(), true);
                    capacityJson = snapshot(Arrays.asList(evaluation, capacity, candidates));
                    after = "PUBLISHED";
                } else {
                    after = "STALE";
                    staleReason = stale;
                }
            }
        } catch (CheckedException exception) {
            throw new PlnMpsRemainingRejectedException(request, "REVIEW", command.getId().toString(), payload, exception.getMessage());
        }
        record.setStatus(after).setReviewRequestId(request).setReviewRequestFingerprint(fingerprint)
                .setReviewedBy(operator()).setReviewedTime(LocalDateTime.now()).setReviewReason(explanation)
                .setReviewChecksSnapshot(snapshot(Arrays.asList(checks.stream().sorted().toList(), risk, staleReason)));
        if (capacityJson != null)
            record.setReviewCapacitySnapshot(capacityJson).setReviewCapacityFingerprint(hash(capacityJson));
        require(updateById(record), "独立审核回执保存失败");
        if ("PUBLISHED".equals(after)) {
            var head = locked.head();
            // head已在发布锁内行锁定，仍按唯一编制前版匹配，不恢复旧版、不改旧PUBLISHED证据。
            require(Objects.equals(head.getCurrentCommitmentId(), record.getPreviousCommitmentId()), "生效指针并发变化");
            head.setCurrentCommitmentId(record.getId()).setPublicationVersion(Math.addExact(head.getPublicationVersion(), 1))
                    .setPublicationRequestId(request);
            require(headMapper.updateById(head) == 1, "原桶当前生效指针切换失败");
        }
        var detail = detailInternal(record);
        audit(record, request, fingerprint, "REVIEW", "PENDING", after, payload, detail);
        return detail;
    }

    /**
     * 编制只限制新引用观察的30分钟；审批核对冻结原基线，不以当前数覆盖原B。
     */
    private Prepared prepare(PlnMpsRemainingCommitmentDTO.PreviewCommand command, boolean enforceCapacity) {
        require(command != null && command.getObservationId() != null && command.getLineId() != null, "原确认观察及精确原桶必填");
        var reference = evidenceReader.read(command.getObservationId(), command.getLineId());
        require("ACTIVE".equals(reference.progress().getResult()), "当前数量不能编制新正量责任：" + reference.progress().getResult()
                + "；" + String.join("；", reference.progress().getGaps()));
        var original = original(command.getLineId(), false);
        require(Boolean.TRUE.equals(original.getActive()) && Boolean.FALSE.equals(original.getPending()), "仅已生效且非待审的原桶可重承诺");
        var head = head(command.getLineId(), false);
        Long currentId = head == null ? null : head.getCurrentCommitmentId();
        require(Objects.equals(command.getPreviousCommitmentId(), currentId), "须精确匹配当前生效前版，不可覆盖并发新版本");
        var policy = policy();
        var schedules = normalized(command.getSchedules());
        dates(schedules, reference.baseline(), policy);
        var evaluation = calculator.evaluate(reference.baseline(), reference.current(), schedules,
                LocalDateTime.now(), reference.observation().getToleranceMinutes());
        require(evaluation.getGaps().isEmpty(), "当前来源/基线不可核验：" + String.join("；", evaluation.getGaps()));
        var candidates = candidates(original, evaluation);
        var capacity = assess(policy, candidates, original.getId(), enforceCapacity);
        String intent = hash(snapshot(Arrays.asList("MPS_REMAINING_INTENT_V1", TenantContextHolder.getTenantId(), factory(),
                command.getObservationId(), reference.observation().getEvidenceFingerprint(), reference.baseline(),
                command.getPreviousCommitmentId(), policySnapshot(policy), schedules)));
        var basis = new PlnMpsRemainingCommitmentDTO.BaselinePreview().setObservationId(command.getObservationId().toString())
                .setVersionId(original.getVersionId().toString()).setToleranceMinutes(reference.observation().getToleranceMinutes())
                .setBaseline(reference.baseline()).setCurrent(reference.progress()).setQuantityReady(true);
        var preview = new PlnMpsRemainingCommitmentDTO.Preview().setBasis(basis).setPolicy(policy)
                .setCurrentCommitmentId(currentId == null ? null : currentId.toString()).setSchedules(schedules)
                .setCapacity(capacity).setIntentFingerprint(intent);
        return new Prepared(reference, preview, evaluation, candidates);
    }

    private PlnMpsCapacityDTO.Preview assess(PlnMpsPolicyEntity policy, List<PlnMpsLineEntity> candidates, Long lineId, boolean enforce) {
        if (candidates.isEmpty())
            return new PlnMpsCapacityDTO.Preview().setMode(StrUtil.blankToDefault(policy.getRccpMode(), "OFF"))
                    .setMaxLoadRate(policy.getRccpMaxLoadRate()).setOverallStatus("FULFILLED").setLines(List.of());
        return capacityService.previewRemaining(factory(), policy, candidates, lineId, enforce);
    }

    /**
     * 只把当前未兑现量用于粗能力预测；原日桶正量完整保存在不可变账。
     */
    private List<PlnMpsLineEntity> candidates(PlnMpsLineEntity original, PlnMpsRemainingCommitmentDTO.Evaluation evaluation) {
        List<PlnMpsLineEntity> rows = new ArrayList<>();
        for (var schedule : evaluation.getBuckets()) {
            BigDecimal remaining = quantity(schedule.getRemainingQuantity());
            if (remaining.signum() == 0) continue;
            var row = BeanUtil.toBean(original, PlnMpsLineEntity.class).setPlanQuantity(remaining)
                    .setPlannedDate(LocalDate.parse(schedule.getPromisedDate())).setBucketNo(schedule.getBucketNo())
                    .setBucketKey("MPSR_CANDIDATE:" + original.getId() + ":" + schedule.getBucketNo())
                    .setCapacityOriginalLineId(original.getId()).setCapacityOriginalDate(original.getPlannedDate())
                    .setCapacityResponsibilityType("REMAINING_CANDIDATE").setCapacityVerified(false)
                    .setCapacityDemandMinutes(null).setCapacityEvidenceSnapshot(null).setCapacityEvidenceFingerprint(null);
            rows.add(row);
        }
        return rows;
    }

    private List<PlnMpsRemainingCommitmentDTO.Schedule> normalized(List<PlnMpsRemainingCommitmentDTO.Schedule> rows) {
        require(rows != null && !rows.isEmpty() && rows.size() <= 100 && rows.stream().allMatch(Objects::nonNull), "完整内部日桶须1至100条");
        return rows.stream().map(row -> {
            require(row.getBucketNo() != null && row.getBucketNo() >= 1 && row.getBucketNo() <= 100, "内部桶序号须1至100");
            String date = text(row.getPromisedDate(), 10, "承诺ISO日期");
            require(date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "日期须完整ISO日期");
            try {
                LocalDate.parse(date);
            } catch (RuntimeException exception) {
                throw new CheckedException("内部承诺日期不存在");
            }
            BigDecimal quantity = quantity(StrUtil.trim(row.getQuantity()));
            require(quantity.signum() > 0, "内部责任桶须正量");
            return new PlnMpsRemainingCommitmentDTO.Schedule().setBucketNo(row.getBucketNo()).setPromisedDate(date).setQuantity(decimal(quantity));
        }).sorted(Comparator.comparing(PlnMpsRemainingCommitmentDTO.Schedule::getBucketNo)).toList();
    }

    private void dates(List<PlnMpsRemainingCommitmentDTO.Schedule> rows, PlnMpsRemainingCommitmentDTO.Baseline baseline, PlnMpsPolicyEntity policy) {
        LocalDate today = LocalDate.now();
        LocalDate original = LocalDate.parse(baseline.getPlannedDate());
        for (var row : rows) {
            LocalDate date = LocalDate.parse(row.getPromisedDate());
            require(!date.isBefore(today) && date.isBefore(today.plusDays(policy.getHorizonDays())), "内部承诺须今日起且在当前滚动窗口内");
            require(date.equals(original) && !original.isBefore(today) || date.isAfter(today.plusDays(policy.getFreezeDays())),
                    "冻结区内只能承接尚未过期的原计划日，改期须先走工厂策略治理");
        }
    }

    private PlnMpsPolicyEntity policy() {
        var policy = mpsService.policy(factory());
        require(policy != null && policy.getHorizonDays() != null && policy.getHorizonDays() > 0
                && policy.getFreezeDays() != null && policy.getFreezeDays() >= 0 && policy.getPolicyVersion() != null, "实际工厂MPS策略未完整配置");
        return policy;
    }

    private String policySnapshot(PlnMpsPolicyEntity policy) {
        return snapshot(Arrays.asList(policy.getId(), policy.getPolicyVersion(), policy.getFactoryCode(), policy.getHorizonDays(),
                policy.getFreezeDays(), policy.getEnforceMps(), StrUtil.blankToDefault(policy.getRccpMode(), "OFF"), decimal(policy.getRccpMaxLoadRate())));
    }

    private Locked lockOriginalAndHead(Long lineId) {
        var original = original(lineId, true);
        var head = head(lineId, true);
        if (head == null) {
            head = new PlnMpsRemainingCommitmentHeadEntity().setMpsLineId(lineId).setPublicationVersion(0).setFactoryCode(factory());
            head.setTenantId(TenantContextHolder.getTenantId());
            head.setCreateBy(operator());
            head.setCreateTime(LocalDateTime.now());
            require(headMapper.insert(head) == 1, "原桶版本指针初始化失败");
        }
        return new Locked(original, head);
    }

    private PlnMpsLineEntity original(Long id, boolean lock) {
        require(id != null, "精确原桶主键必填");
        var query = new LambdaQueryWrapper<PlnMpsLineEntity>().eq(PlnMpsLineEntity::getId, id)
                .eq(PlnMpsLineEntity::getTenantId, TenantContextHolder.getTenantId()).eq(PlnMpsLineEntity::getFactoryCode, factory());
        if (lock) query.last("FOR UPDATE");
        var row = originalMapper.selectOne(query);
        require(row != null, "实际组织未找到精确原MPS桶");
        return row;
    }

    private PlnMpsRemainingCommitmentHeadEntity head(Long lineId, boolean lock) {
        var query = new LambdaQueryWrapper<PlnMpsRemainingCommitmentHeadEntity>()
                .eq(PlnMpsRemainingCommitmentHeadEntity::getMpsLineId, lineId)
                .eq(PlnMpsRemainingCommitmentHeadEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnMpsRemainingCommitmentHeadEntity::getFactoryCode, factory());
        if (lock) query.last("FOR UPDATE");
        return headMapper.selectOne(query);
    }

    private List<PlnMpsRemainingCommitmentLineEntity> lines(Long id) {
        var rows = scheduleMapper.selectList(new LambdaQueryWrapper<PlnMpsRemainingCommitmentLineEntity>()
                .eq(PlnMpsRemainingCommitmentLineEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnMpsRemainingCommitmentLineEntity::getFactoryCode, factory()).eq(PlnMpsRemainingCommitmentLineEntity::getCommitmentId, id)
                .orderByAsc(PlnMpsRemainingCommitmentLineEntity::getBucketNo).last("LIMIT 101"));
        require(!rows.isEmpty() && rows.size() <= 100, "剩余版本缺完整内部日桶或超限");
        return rows;
    }

    private PlnMpsRemainingCommitmentDTO.Detail detailInternal(PlnMpsRemainingCommitmentEntity record) {
        var head = head(record.getMpsLineId(), false);
        Long currentId = head == null ? null : head.getCurrentCommitmentId();
        return new PlnMpsRemainingCommitmentDTO.Detail().setRecord(record).setLines(lines(record.getId()))
                .setCurrentCommitmentId(currentId == null ? null : currentId.toString()).setCurrent(Objects.equals(record.getId(), currentId));
    }

    private PlnMpsRemainingCommitmentEntity record(Long id, boolean lock) {
        require(id != null, "剩余版本主键必填");
        var query = scope().eq(PlnMpsRemainingCommitmentEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        var row = getOne(query);
        require(row != null, "实际租户/工厂未找到剩余责任版本");
        return row;
    }

    private LambdaQueryWrapper<PlnMpsRemainingCommitmentEntity> scope() {
        return new LambdaQueryWrapper<PlnMpsRemainingCommitmentEntity>().eq(PlnMpsRemainingCommitmentEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnMpsRemainingCommitmentEntity::getFactoryCode, factory());
    }

    private PlnMpsRemainingCommitmentOperationEntity operation(String request) {
        return operationMapper.selectOne(new LambdaQueryWrapper<PlnMpsRemainingCommitmentOperationEntity>()
                .eq(PlnMpsRemainingCommitmentOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnMpsRemainingCommitmentOperationEntity::getFactoryCode, factory()).eq(PlnMpsRemainingCommitmentOperationEntity::getRequestId, request));
    }

    private PlnMpsRemainingCommitmentDTO.Detail replay(String request, String fingerprint) {
        var operation = operation(request);
        if (operation == null) return null;
        require(Objects.equals(operation.getRequestFingerprint(), fingerprint) && Objects.equals(operation.getOperatorCode(), operator()),
                "永久命令号已用于不同载荷/动作或实名，不得换号覆盖未知结果");
        require(operation.getReceiptSnapshot() != null, "原永久号缺完整回执，须对账，不能重新执行");
        return JSONUtil.toBean(operation.getReceiptSnapshot(), PlnMpsRemainingCommitmentDTO.Detail.class);
    }

    private void audit(PlnMpsRemainingCommitmentEntity record, String request, String fingerprint, String type,
                       String before, String after, String command, PlnMpsRemainingCommitmentDTO.Detail receipt) {
        var operation = new PlnMpsRemainingCommitmentOperationEntity().setCommitmentId(record.getId()).setMpsLineId(record.getMpsLineId())
                .setRequestId(request).setRequestFingerprint(fingerprint).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setOperatorCode(operator()).setCommandSnapshot(command).setReceiptSnapshot(snapshot(receipt))
                .setOperatedTime(LocalDateTime.now()).setFactoryCode(factory());
        operation.setTenantId(TenantContextHolder.getTenantId());
        operation.setCreateBy(operator());
        operation.setCreateTime(LocalDateTime.now());
        require(operationMapper.insert(operation) == 1, "永久命令回执落账失败，整体事务回滚");
    }

    private void lockRequest(String request) {
        requestLockMapper.lockKey("REMAINING_COMMAND_V1:" + TenantContextHolder.getTenantId() + ":" + factory() + ":" + request);
    }

    private String fingerprint(String type, String payload) {
        return hash(snapshot(Arrays.asList("MPS_REMAINING_COMMAND_V1", TenantContextHolder.getTenantId(), factory(), operator(), type, payload)));
    }

    private void authorize(String level) {
        mpsService.requireFactoryAccess(factory(), level);
    }

    private String factory() {
        return sourceReader.actualFactory();
    }

    private String operator() {
        return mpsService.currentUser().getUsername();
    }

    private String text(String value, int max, String name) {
        String result = StrUtil.trim(value);
        require(StrUtil.isNotBlank(result) && result.length() <= max, name + "不能为空或超长");
        return result;
    }

    private String idText(Long id) {
        return id == null ? null : id.toString();
    }

    private record Locked(PlnMpsLineEntity original, PlnMpsRemainingCommitmentHeadEntity head) {
    }

    private record Prepared(PlnMpsRemainingCommitmentEvidenceReader.Reference reference,
                            PlnMpsRemainingCommitmentDTO.Preview preview,
                            PlnMpsRemainingCommitmentDTO.Evaluation evaluation, List<PlnMpsLineEntity> candidates) {
    }
}
