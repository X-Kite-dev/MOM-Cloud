package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.PlnMpsRemainingCommitmentDTO;
import com.kitex.mom.plan.entity.PlnMpsRemainingFulfillmentObservationEntity;
import com.kitex.mom.plan.exception.PlnMpsRemainingRejectedException;
import com.kitex.mom.plan.mapper.PlnMpsPolicyMapper;
import com.kitex.mom.plan.mapper.PlnMpsRemainingFulfillmentObservationMapper;
import com.kitex.mom.plan.service.PlnMpsRemainingCommitmentService;
import com.kitex.mom.plan.service.PlnMpsRemainingFulfillmentObservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static com.kitex.mom.plan.service.impl.PlnMpsRemainingSnapshotSupport.*;

/**
 * 分期兑现采集与异人核验；冻结原观察，变化时新采集，不覆盖原事实。
 */
@Service
@RequiredArgsConstructor
public class PlnMpsRemainingFulfillmentObservationServiceImpl extends ServiceImpl<PlnMpsRemainingFulfillmentObservationMapper, PlnMpsRemainingFulfillmentObservationEntity>
        implements PlnMpsRemainingFulfillmentObservationService {
    private static final Set<String> CHECKS = Set.of("SOURCE_BASELINE_VALID", "QUANTITY_CONSERVED",
            "TIME_BASIS_DISTINGUISHED", "ORIGINAL_PROMISE_PRESERVED");
    private final PlnMpsRemainingCommitmentService commitmentService;
    private final PlnMpsExecutionSourceReader sourceReader;
    private final PlnMpsExecutionEvidenceReader executionReader;
    private final PlnMpsRemainingFulfillmentCalculator calculator;
    private final PlnMpsService mpsService;
    private final PlnMpsPolicyMapper requestLockMapper;
    private final PlnMpsCapacityPublicationLock publicationLock;

    @Override
    public PlnMpsRemainingCommitmentDTO.FulfillmentEvidence preview(Long commitmentId) {
        authorize("read");
        var detail = commitmentService.detail(commitmentId);
        var record = detail.getRecord();
        require("PUBLISHED".equals(record.getStatus()), "兑现须已发布版本，待审不是正式承诺");
        var current = sourceReader.readBuckets(List.of(record.getMpsLineId()), record.getToleranceMinutes()).get(0);
        var baseline = baseline(record);
        LocalDateTime now = LocalDateTime.now();
        var evidence = new PlnMpsRemainingCommitmentDTO.FulfillmentEvidence().setCommitmentId(record.getId().toString())
                .setLineId(record.getMpsLineId().toString()).setCommitmentVersion(record.getCommitmentVersion())
                .setCurrent(detail.getCurrent()).setCurrentCommitmentId(detail.getCurrentCommitmentId()).setBaseline(baseline)
                .setOriginal(current).setEvaluation(calculator.evaluate(baseline, current, schedules(detail.getLines()), now, record.getToleranceMinutes()))
                .setObservedAt(now.toString()).setToleranceMinutes(record.getToleranceMinutes());
        return evidence.setFingerprint(evidenceFingerprint(evidence));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsRemainingFulfillmentObservationEntity capture(PlnMpsRemainingCommitmentDTO.FulfillmentCapture command) {
        authorize("write");
        require(command != null && command.getCommitmentId() != null, "当前生效剩余版本必填");
        String request = text(command.getRequestId(), 100, "永久采集号");
        String reason = text(command.getReason(), 2000, "采集依据");
        String payload = commandSnapshot(Arrays.asList(command.getCommitmentId().toString(), reason));
        String fingerprint = commandFingerprint("CAPTURE", JSONUtil.parseArray(payload));
        lockRequest(request);
        var replay = findRequest(request);
        if (replay != null) {
            require(Objects.equals(replay.getRequestId(), request) && Objects.equals(replay.getRequestFingerprint(), fingerprint)
                    && Objects.equals(replay.getCreateBy(), operator()), "原号已用于其他采集/核验载荷或实名");
            return replay;
        }
        publicationLock.acquire(factory()); // 在观察落账前稳定当前生效版本，遵守请求→发布→业务行顺序。
        PlnMpsRemainingFulfillmentObservationEntity record;
        try {
            var evidence = preview(command.getCommitmentId());
            require(Boolean.TRUE.equals(evidence.getCurrent()), "旧版只读历史，当前观察须引用新生效版本");
            record = new PlnMpsRemainingFulfillmentObservationEntity().setObservationNo("MPSRF-" + IdUtil.getSnowflakeNextIdStr())
                    .setCommitmentId(command.getCommitmentId()).setMpsLineId(Long.valueOf(evidence.getLineId()))
                    .setRequestId(request).setRequestFingerprint(fingerprint).setToleranceMinutes(evidence.getToleranceMinutes())
                    .setObservedTime(LocalDateTime.parse(evidence.getObservedAt())).setBaselineFingerprint(hash(snapshot(evidence.getBaseline())))
                    .setEvidenceFingerprint(evidence.getFingerprint()).setEvidenceSnapshot(snapshot(evidence)).setResult(evidence.getEvaluation().getResult())
                    .setCurrentQuantity(evidence.getEvaluation().getCurrentQuantity()).setRemainingQuantity(evidence.getEvaluation().getRemainingQuantity())
                    .setBucketCount(evidence.getEvaluation().getBuckets().size()).setStatus("PENDING").setReason(reason).setFactoryCode(factory());
        } catch (CheckedException exception) {
            throw new PlnMpsRemainingRejectedException(request, "CAPTURE", command.getCommitmentId().toString(), payload, exception.getMessage());
        }
        record.setTenantId(TenantContextHolder.getTenantId());
        record.setCreateBy(operator());
        record.setCreateTime(LocalDateTime.now());
        require(save(record), "剩余分期完整观察落账失败");
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnMpsRemainingFulfillmentObservationEntity review(PlnMpsRemainingCommitmentDTO.FulfillmentReview command) {
        authorize("write");
        require(command != null && command.getId() != null, "分期观察主键必填");
        String request = text(command.getRequestId(), 100, "永久核验号");
        String remark = text(command.getRemark(), 2000, "核验依据");
        require(Set.of("CONFIRM", "REJECT").contains(StrUtil.blankToDefault(command.getDecision(), "")), "核验只能确认或驳回");
        var checks = command.getChecks() == null ? List.<String>of() : command.getChecks();
        require(checks.stream().allMatch(Objects::nonNull) && CHECKS.containsAll(checks)
                && checks.stream().distinct().count() == checks.size(), "核验目录重复或非法");
        String payload = commandSnapshot(Arrays.asList(command.getId().toString(), command.getDecision(), checks.stream().sorted().toList(), remark));
        String fingerprint = commandFingerprint("REVIEW", JSONUtil.parseArray(payload));
        lockRequest(request);
        var replay = findRequest(request);
        if (replay != null) {
            require(Objects.equals(replay.getId(), command.getId()) && Objects.equals(replay.getReviewRequestId(), request)
                            && Objects.equals(replay.getReviewRequestFingerprint(), fingerprint) && Objects.equals(replay.getReviewedBy(), operator()),
                    "原核验号已用于不同观察/载荷或实名");
            return replay;
        }
        publicationLock.acquire(factory());
        var record = record(command.getId(), true);
        String status = "REJECTED";
        String staleReason = null;
        try {
            require("PENDING".equals(record.getStatus()) && !Objects.equals(record.getCreateBy(), operator()), "只允许另一实名岗位核验待审观察");
            if ("CONFIRM".equals(command.getDecision())) {
                require(new HashSet<>(checks).equals(CHECKS), "须核对来源、数量、时间依据和原承诺保留四项");
                var frozen = JSONUtil.toBean(record.getEvidenceSnapshot(), PlnMpsRemainingCommitmentDTO.FulfillmentEvidence.class);
                require(Objects.equals(frozen.getCommitmentId(), record.getCommitmentId().toString())
                        && Objects.equals(frozen.getLineId(), record.getMpsLineId().toString())
                        && Objects.equals(frozen.getFingerprint(), record.getEvidenceFingerprint())
                        && Objects.equals(evidenceFingerprint(frozen), record.getEvidenceFingerprint()), "原观察正文/原桶/指纹损坏");
                Duration age = Duration.between(record.getObservedTime(), LocalDateTime.now());
                if (age.isNegative() || age.compareTo(Duration.ofMinutes(30)) > 0) {
                    status = "STALE";
                    staleReason = "超过30分钟，须新采集，不修改旧观察";
                } else {
                    var latest = preview(record.getCommitmentId());
                    require(!"DATA_GAP".equals(latest.getEvaluation().getResult()), "当前取证待核，保留待审，不能凭旧成功确认");
                    boolean valid = Boolean.TRUE.equals(latest.getCurrent())
                            && Set.of("ACTIVE", "FULFILLED").contains(latest.getEvaluation().getResult())
                            && Objects.equals(latest.getFingerprint(), record.getEvidenceFingerprint());
                    status = valid ? "CONFIRMED" : "STALE";
                    staleReason = valid ? null : "来源、日期结论或当前版本已变化，须新采集";
                }
            }
        } catch (CheckedException exception) {
            throw new PlnMpsRemainingRejectedException(request, "FULFILLMENT_REVIEW", command.getId().toString(), payload, exception.getMessage());
        }
        record.setStatus(status).setReviewRequestId(request).setReviewRequestFingerprint(fingerprint)
                .setReviewedBy(operator()).setReviewedTime(LocalDateTime.now()).setReviewRemark(remark)
                .setReviewChecksSnapshot(snapshot(Arrays.asList(checks.stream().sorted().toList(), staleReason)));
        require(updateById(record), "独立核验落账失败");
        return record;
    }

    @Override
    public PlnMpsRemainingFulfillmentObservationEntity detail(Long id) {
        authorize("read");
        return record(id, false);
    }

    @Override
    public Page<PlnMpsRemainingFulfillmentObservationEntity> history(Long commitmentId, long current, long size) {
        authorize("read");
        require(commitmentId != null && current > 0 && size > 0 && size <= 100, "版本必填，每页最多100条");
        commitmentService.detail(commitmentId);
        return page(new Page<>(current, size), scope().eq(PlnMpsRemainingFulfillmentObservationEntity::getCommitmentId, commitmentId)
                .select(PlnMpsRemainingFulfillmentObservationEntity.class, field -> !"evidenceSnapshot".equals(field.getProperty()))
                .orderByDesc(PlnMpsRemainingFulfillmentObservationEntity::getObservedTime).orderByDesc(PlnMpsRemainingFulfillmentObservationEntity::getId));
    }

    @Override
    public PlnMpsRemainingFulfillmentObservationEntity byRequest(String requestId) {
        authorize("read");
        return findRequest(text(requestId, 100, "永久号"));
    }

    private String evidenceFingerprint(PlnMpsRemainingCommitmentDTO.FulfillmentEvidence evidence) {
        return hash(snapshot(Arrays.asList("MPS_REMAINING_FULFILLMENT_V1", TenantContextHolder.getTenantId(), factory(),
                evidence.getCommitmentId(), evidence.getLineId(), evidence.getCommitmentVersion(), evidence.getCurrent(),
                evidence.getCurrentCommitmentId(), evidence.getBaseline(), evidence.getToleranceMinutes(),
                executionReader.bucketFingerprint(evidence.getOriginal()), evidence.getEvaluation())));
    }

    private String commandFingerprint(String type, Object payload) {
        return hash(snapshot(Arrays.asList("MPS_REMAINING_OBSERVATION_COMMAND_V1", TenantContextHolder.getTenantId(), factory(), operator(), type, payload)));
    }

    private void lockRequest(String request) {
        requestLockMapper.lockKey("REMAINING_OBSERVATION_V1:" + TenantContextHolder.getTenantId() + ":" + factory() + ":" + request);
    }

    private PlnMpsRemainingFulfillmentObservationEntity findRequest(String request) {
        return getOne(scope().and(query -> query.eq(PlnMpsRemainingFulfillmentObservationEntity::getRequestId, request)
                .or().eq(PlnMpsRemainingFulfillmentObservationEntity::getReviewRequestId, request)));
    }

    private PlnMpsRemainingFulfillmentObservationEntity record(Long id, boolean lock) {
        require(id != null, "分期观察主键必填");
        var query = scope().eq(PlnMpsRemainingFulfillmentObservationEntity::getId, id);
        if (lock) query.last("FOR UPDATE");
        var record = getOne(query);
        require(record != null, "实际组织未找到分期观察");
        return record;
    }

    private LambdaQueryWrapper<PlnMpsRemainingFulfillmentObservationEntity> scope() {
        return new LambdaQueryWrapper<PlnMpsRemainingFulfillmentObservationEntity>()
                .eq(PlnMpsRemainingFulfillmentObservationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnMpsRemainingFulfillmentObservationEntity::getFactoryCode, factory());
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

    private String text(String value, int max, String label) {
        String text = StrUtil.trim(value);
        require(StrUtil.isNotBlank(text) && text.length() <= max, label + "不能为空或超长");
        return text;
    }
}
