package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmDurationCalibrationDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmDurationCalibrationService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesDurationEvidenceDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MdmDurationCalibrationServiceImpl implements MdmDurationCalibrationService {
    private static final Set<String> TARGETS = Set.of("OPERATION", "CHANGEOVER");
    private static final Set<String> OPERATION_SCOPES = Set.of("PROCESS", "STATION", "DEVICE");
    private static final Set<String> CHANGEOVER_SCOPES = Set.of("STATION", "DEVICE", "TOOLING");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final MdmDurationCalibrationPolicyMapper policyMapper;
    private final MdmDurationCalibrationRunMapper runMapper;
    private final MdmDurationCalibrationProposalMapper proposalMapper;
    private final MdmDurationCalibrationSampleMapper sampleMapper;
    private final MdmDurationCalibrationLogMapper logMapper;
    private final MdmProcedureMapper procedureMapper;
    private final MdmStationSettingMapper stationMapper;
    private final MdmDeviceMapper deviceMapper;
    private final MdmToolingMapper toolingMapper;
    private final MdmSchedulingFamilyMapper familyMapper;
    private final MdmChangeoverRuleMapper changeoverRuleMapper;
    private final MdmChangeoverRuleLogMapper changeoverRuleLogMapper;
    private final RemoteMesTaskService remoteMesTaskService;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long savePolicy(MdmDurationCalibrationPolicyEntity policy) {
        if (policy == null) throw new CheckedException("校准策略不能为空");
        policy.setFactoryCode(requireFactory());
        normalizePolicy(policy);
        assertPolicyVersionUnique(policy);
        policy.setId(null);
        policy.setStatus("DRAFT");
        clearPolicyLifecycle(policy);
        policyMapper.insert(policy);
        log("POLICY", policy.getId(), "CREATE", null, "DRAFT", null, null, JSONUtil.toJsonStr(policy),
                policy.getFactoryCode());
        return policy.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePolicy(MdmDurationCalibrationPolicyEntity policy) {
        if (policy == null || policy.getId() == null) throw new CheckedException("校准策略ID不能为空");
        MdmDurationCalibrationPolicyEntity current = requirePolicy(policy.getId());
        if (!"DRAFT".equals(current.getStatus())) throw new CheckedException("只有草稿策略可以修改");
        String before = JSONUtil.toJsonStr(current);
        policy.setFactoryCode(current.getFactoryCode());
        policy.setStatus("DRAFT");
        normalizePolicy(policy);
        assertPolicyVersionUnique(policy);
        clearPolicyLifecycle(policy);
        policyMapper.updateById(policy);
        log("POLICY", policy.getId(), "UPDATE", "DRAFT", "DRAFT", null, before,
                JSONUtil.toJsonStr(policy), current.getFactoryCode());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changePolicyStatus(MdmDurationCalibrationDTO.PolicyAction action) {
        if (action == null || action.getId() == null || StrUtil.isBlank(action.getAction())) {
            throw new CheckedException("策略状态操作缺少ID或动作");
        }
        MdmDurationCalibrationPolicyEntity policy = requirePolicy(action.getId());
        String command = action.getAction().trim().toUpperCase(Locale.ROOT);
        String from = policy.getStatus();
        String before = JSONUtil.toJsonStr(policy);
        MomUser user = SecurityUtils.getUser();
        LocalDateTime now = LocalDateTime.now();
        if ("ACTIVATE".equals(command)) {
            if (!"DRAFT".equals(from)) throw new CheckedException("只有草稿策略可以启用");
            if (Objects.equals(StrUtil.trim(policy.getCreateBy()), user.getUsername())) {
                throw new CheckedException("校准策略编制人与启用人必须岗位分离");
            }
            normalizePolicy(policy);
            List<MdmDurationCalibrationPolicyEntity> active = policyMapper.selectList(
                    new LambdaQueryWrapper<MdmDurationCalibrationPolicyEntity>()
                            .eq(MdmDurationCalibrationPolicyEntity::getFactoryCode, policy.getFactoryCode())
                            .eq(MdmDurationCalibrationPolicyEntity::getTargetType, policy.getTargetType())
                            .eq(MdmDurationCalibrationPolicyEntity::getScopeType, policy.getScopeType())
                            .eq(MdmDurationCalibrationPolicyEntity::getStatus, "ACTIVE"));
            boolean collision = active.stream().anyMatch(item ->
                    Objects.equals(StrUtil.emptyToNull(item.getProcessCode()), StrUtil.emptyToNull(policy.getProcessCode()))
                            && Objects.equals(StrUtil.emptyToNull(item.getResourceCode()), StrUtil.emptyToNull(policy.getResourceCode())));
            if (collision) throw new CheckedException("同目标、范围和过滤条件已有启用的校准策略，请先退役旧版本");
            policy.setStatus("ACTIVE");
            policy.setApprovedBy(user.getUsername());
            policy.setApprovedByName(user.getName());
            policy.setApprovedTime(now);
        } else if ("RETIRE".equals(command)) {
            if (!"ACTIVE".equals(from)) throw new CheckedException("只有启用策略可以退役");
            if (StrUtil.isBlank(action.getReason())) throw new CheckedException("退役策略必须填写原因");
            policy.setStatus("RETIRED");
            policy.setCloseReason(trim500(action.getReason()));
            policy.setClosedBy(user.getUsername());
            policy.setClosedByName(user.getName());
            policy.setClosedTime(now);
        } else {
            throw new CheckedException("不支持的策略动作：" + command);
        }
        policyMapper.updateById(policy);
        log("POLICY", policy.getId(), command, from, policy.getStatus(), action.getReason(), before,
                JSONUtil.toJsonStr(policy), policy.getFactoryCode());
        return true;
    }

    @Override
    public MdmDurationCalibrationRunEntity execute(MdmDurationCalibrationDTO.RunCommand command) {
        if (command == null || command.getPolicyId() == null) throw new CheckedException("校准运行必须选择策略");
        MdmDurationCalibrationPolicyEntity policy = requirePolicy(command.getPolicyId());
        if (!"ACTIVE".equals(policy.getStatus())) throw new CheckedException("只有启用策略可以执行校准");
        LocalDateTime to = command.getToTime() == null ? LocalDateTime.now() : command.getToTime();
        LocalDateTime from = command.getFromTime() == null ? to.minusDays(policy.getLookbackDays()) : command.getFromTime();
        if (!to.isAfter(from) || Duration.between(from, to).toDays() > 366) {
            throw new CheckedException("样本时间范围必须大于0且不超过366天");
        }
        MomUser user = SecurityUtils.getUser();
        MdmDurationCalibrationRunEntity run = new MdmDurationCalibrationRunEntity();
        run.setRunNo("DCR-" + NO_TIME.format(LocalDateTime.now()));
        run.setPolicyId(policy.getId());
        run.setPolicyNo(policy.getPolicyNo());
        run.setPolicyVersionNo(policy.getVersionNo());
        run.setTargetType(policy.getTargetType());
        run.setScopeType(policy.getScopeType());
        run.setProcessCode(policy.getProcessCode());
        run.setResourceCode(policy.getResourceCode());
        run.setSampleStartTime(from);
        run.setSampleEndTime(to);
        run.setStatus("RUNNING");
        run.setPolicySnapshot(JSONUtil.toJsonStr(policy));
        run.setRequestedBy(user.getUsername());
        run.setRequestedByName(user.getName());
        run.setRequestedTime(LocalDateTime.now());
        run.setFactoryCode(policy.getFactoryCode());
        runMapper.insert(run);
        try {
            MesDurationEvidenceDTO.Bundle evidence = fetchEvidence(policy, from, to);
            transactionTemplate.executeWithoutResult(status -> persistRun(policy, run, evidence));
            return runMapper.selectById(run.getId());
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setFailureReason(StrUtil.sub(StrUtil.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 0, 1000));
            run.setCompletedTime(LocalDateTime.now());
            runMapper.updateById(run);
            log("RUN", run.getId(), "FAIL", "RUNNING", "FAILED", run.getFailureReason(), null,
                    JSONUtil.toJsonStr(run), run.getFactoryCode());
            if (ex instanceof CheckedException checked) throw checked;
            throw new CheckedException("校准运行失败：" + run.getFailureReason());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateProposal(MdmDurationCalibrationDTO.ProposalUpdate command) {
        if (command == null || command.getId() == null) throw new CheckedException("校准建议ID不能为空");
        MdmDurationCalibrationProposalEntity row = requireProposal(command.getId());
        if (!"DRAFT".equals(row.getStatus())) throw new CheckedException("只有草稿建议可以调整");
        String before = JSONUtil.toJsonStr(row);
        if (command.getProposedFixedSeconds() != null) row.setProposedFixedSeconds(command.getProposedFixedSeconds());
        if (command.getProposedUnitSeconds() != null) row.setProposedUnitSeconds(command.getProposedUnitSeconds());
        if (command.getProposedChangeoverSeconds() != null)
            row.setProposedChangeoverSeconds(command.getProposedChangeoverSeconds());
        if (command.getCleaningRatioPct() != null) row.setCleaningRatioPct(command.getCleaningRatioPct());
        if (command.getEffectiveTime() != null) row.setEffectiveTime(command.getEffectiveTime());
        row.setReviewComment(trim500(command.getReviewComment()));
        if ("CHANGEOVER".equals(row.getTargetType())) {
            if (StrUtil.isNotBlank(command.getScopeType())) row.setScopeType(upper(command.getScopeType()));
            if (command.getResourceCode() != null) row.setResourceCode(StrUtil.trim(command.getResourceCode()));
            if (command.getProcessCode() != null) row.setProcessCode(StrUtil.trim(command.getProcessCode()));
            if (command.getFromFamilyCode() != null) row.setFromFamilyCode(StrUtil.trim(command.getFromFamilyCode()));
            if (command.getToFamilyCode() != null) row.setToFamilyCode(StrUtil.trim(command.getToFamilyCode()));
            resolveChangeoverTarget(row);
            refreshCurrentChangeover(row);
        }
        validateEditableValues(row);
        refreshPublishEligibility(row);
        proposalMapper.updateById(row);
        log("PROPOSAL", row.getId(), "UPDATE", "DRAFT", "DRAFT", command.getReviewComment(), before,
                JSONUtil.toJsonStr(row), row.getFactoryCode());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean changeProposalStatus(MdmDurationCalibrationDTO.ProposalAction action) {
        if (action == null || action.getId() == null || StrUtil.isBlank(action.getAction())) {
            throw new CheckedException("建议状态操作缺少ID或动作");
        }
        MdmDurationCalibrationProposalEntity row = requireProposal(action.getId());
        String command = upper(action.getAction());
        String from = row.getStatus();
        String before = JSONUtil.toJsonStr(row);
        MomUser user = SecurityUtils.getUser();
        if ("SUBMIT".equals(command)) {
            if (!"DRAFT".equals(from)) throw new CheckedException("只有草稿建议可以提交复核");
            refreshPublishEligibility(row);
            if (!Boolean.TRUE.equals(row.getPublishEligible())) {
                throw new CheckedException("建议尚不具备发布条件：" + row.getEligibilityReason());
            }
            verifyEvidenceUnchanged(row);
            row.setStatus("SUBMITTED");
            row.setSubmittedBy(user.getUsername());
            row.setSubmittedByName(user.getName());
            row.setSubmittedTime(LocalDateTime.now());
        } else if ("APPROVE".equals(command)) {
            if (!"SUBMITTED".equals(from)) throw new CheckedException("只有待复核建议可以批准发布");
            if (Objects.equals(row.getSubmittedBy(), user.getUsername())
                    || Objects.equals(StrUtil.trim(row.getCreateBy()), user.getUsername())) {
                throw new CheckedException("建议编制/提交人与批准发布人必须岗位分离");
            }
            verifyEvidenceUnchanged(row);
            refreshPublishEligibility(row);
            if (!Boolean.TRUE.equals(row.getPublishEligible())) {
                throw new CheckedException("建议已不具备发布条件：" + row.getEligibilityReason());
            }
            MdmDurationCalibrationProposalEntity publishTarget = row;
            transactionTemplate.executeWithoutResult(status -> publish(publishTarget, user));
            row = requireProposal(row.getId());
        } else if ("REJECT".equals(command)) {
            if (!"SUBMITTED".equals(from)) throw new CheckedException("只有待复核建议可以驳回");
            if (StrUtil.isBlank(action.getReason())) throw new CheckedException("驳回必须填写原因");
            row.setStatus("REJECTED");
            row.setRejectedBy(user.getUsername());
            row.setRejectedByName(user.getName());
            row.setRejectedTime(LocalDateTime.now());
            row.setReviewComment(trim500(action.getReason()));
        } else if ("RETIRE".equals(command)) {
            if (!"ACTIVE".equals(from)) throw new CheckedException("只有生效版本可以退役");
            if (StrUtil.isBlank(action.getReason())) throw new CheckedException("退役必须填写原因");
            retire(row, user, action.getReason());
        } else {
            throw new CheckedException("不支持的建议动作：" + command);
        }
        if (!"APPROVE".equals(command)) proposalMapper.updateById(row);
        log("PROPOSAL", row.getId(), command, from, row.getStatus(), action.getReason(), before,
                JSONUtil.toJsonStr(row), row.getFactoryCode());
        return true;
    }

    @Override
    public MdmDurationCalibrationDTO.Catalog catalog(MdmDurationCalibrationDTO.CatalogQuery query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())) throw new CheckedException("校准目录缺少工厂");
        LocalDateTime effective = query.getEffectiveTime() == null ? LocalDateTime.now() : query.getEffectiveTime();
        List<MdmDurationCalibrationProposalEntity> rows = proposalMapper.selectList(
                new LambdaQueryWrapper<MdmDurationCalibrationProposalEntity>()
                        .eq(MdmDurationCalibrationProposalEntity::getFactoryCode, query.getFactoryCode().trim())
                        .eq(MdmDurationCalibrationProposalEntity::getTargetType, "OPERATION")
                        .eq(MdmDurationCalibrationProposalEntity::getStatus, "ACTIVE")
                        .le(MdmDurationCalibrationProposalEntity::getEffectiveTime, effective)
                        .in(query.getProcessCodes() != null && !query.getProcessCodes().isEmpty(),
                                MdmDurationCalibrationProposalEntity::getProcessCode, query.getProcessCodes())
                        .orderByDesc(MdmDurationCalibrationProposalEntity::getEffectiveTime)
                        .orderByDesc(MdmDurationCalibrationProposalEntity::getVersionNo));
        Map<String, MdmDurationCalibrationProposalEntity> latest = new LinkedHashMap<>();
        for (MdmDurationCalibrationProposalEntity row : rows) {
            latest.putIfAbsent(profileKey(row), row);
        }
        List<MdmDurationCalibrationProposalEntity> profiles = new ArrayList<>(latest.values());
        String fingerprint = sha256(JSONUtil.toJsonStr(profiles.stream().map(item -> Map.of(
                "id", item.getId(), "version", item.getVersionNo(), "scope", item.getScopeType(),
                "resource", StrUtil.blankToDefault(item.getResourceCode(), ""), "process", item.getProcessCode(),
                "fixedSeconds", item.getProposedFixedSeconds(), "unitSeconds", item.getProposedUnitSeconds(),
                "effectiveTime", item.getEffectiveTime(), "evidence", item.getEvidenceFingerprint())).toList()));
        return new MdmDurationCalibrationDTO.Catalog().setGeneratedTime(LocalDateTime.now())
                .setFingerprint(fingerprint).setProfiles(profiles);
    }

    private void persistRun(MdmDurationCalibrationPolicyEntity policy, MdmDurationCalibrationRunEntity run,
                            MesDurationEvidenceDTO.Bundle evidence) {
        List<? extends Object> sources = "OPERATION".equals(policy.getTargetType())
                ? safe(evidence.getOperationSamples()) : safe(evidence.getChangeoverSamples());
        run.setEvidenceFingerprint(evidence.getEvidenceFingerprint());
        run.setEvidenceGeneratedTime(evidence.getGeneratedTime());
        run.setSourceSampleCount(sources.size());
        run.setSourceExcludedCount("OPERATION".equals(policy.getTargetType())
                ? Optional.ofNullable(evidence.getExcludedOperationCount()).orElse(0)
                : Optional.ofNullable(evidence.getExcludedChangeoverCount()).orElse(0));
        RunBuild build = "OPERATION".equals(policy.getTargetType())
                ? buildOperation(policy, run, safe(evidence.getOperationSamples()))
                : buildChangeover(policy, run, safe(evidence.getChangeoverSamples()));
        run.setStrategyExcludedCount(build.strategyExcluded);
        run.setAcceptedSampleCount(build.accepted);
        run.setProposalCount(build.proposals);
        run.setStatus(build.proposals > 0 ? "COMPLETED" : "NO_PROPOSAL");
        run.setFailureReason(build.proposals > 0 ? null : "没有形成任何可审阅建议；请检查样本量、过滤条件和源数据质量");
        run.setCompletedTime(LocalDateTime.now());
        runMapper.updateById(run);
        log("RUN", run.getId(), "COMPLETE", "RUNNING", run.getStatus(), run.getFailureReason(), null,
                JSONUtil.toJsonStr(run), run.getFactoryCode());
    }

    private RunBuild buildOperation(MdmDurationCalibrationPolicyEntity policy,
                                    MdmDurationCalibrationRunEntity run,
                                    List<MesDurationEvidenceDTO.OperationSample> sources) {
        List<OperationPoint> candidates = new ArrayList<>();
        int strategyExcluded = 0;
        for (MesDurationEvidenceDTO.OperationSample source : sources) {
            MdmDurationCalibrationSampleEntity sample = operationSample(run, source);
            String mismatch = operationMismatch(policy, source);
            if (!Boolean.TRUE.equals(source.getSourceEligible())) {
                sample.setStrategyAccepted(false);
                sample.setExclusionReason(StrUtil.blankToDefault(source.getExclusionReason(), "MES源证据不完整"));
            } else if (mismatch != null) {
                sample.setStrategyAccepted(false);
                sample.setExclusionReason(mismatch);
                strategyExcluded++;
            } else {
                sample.setStrategyAccepted(true);
                BigDecimal rate = BigDecimal.valueOf(source.getActiveSeconds())
                        .divide(source.getCompletedQuantity(), 6, RoundingMode.HALF_UP);
                sample.setNormalizedSeconds(rate);
                candidates.add(new OperationPoint(source, sample, operationGroupKey(policy, source), rate));
            }
            sampleMapper.insert(sample);
        }
        int accepted = 0;
        int proposals = 0;
        Map<String, List<OperationPoint>> groups = candidates.stream()
                .collect(Collectors.groupingBy(OperationPoint::groupKey, LinkedHashMap::new, Collectors.toList()));
        for (List<OperationPoint> group : groups.values()) {
            OutlierResult<OperationPoint> filtered = filterMad(group, OperationPoint::rate, policy);
            markOutliers(filtered.rejected, "单位时长超出MAD阈值");
            strategyExcluded += filtered.rejected.size();
            accepted += filtered.accepted.size();
            MdmDurationCalibrationProposalEntity proposal = operationProposal(policy, run, filtered.accepted,
                    group.size() - filtered.accepted.size());
            proposalMapper.insert(proposal);
            for (OperationPoint point : group) {
                point.sample.setProposalId(proposal.getId());
                sampleMapper.updateById(point.sample);
            }
            proposals++;
        }
        return new RunBuild(strategyExcluded, accepted, proposals);
    }

    private RunBuild buildChangeover(MdmDurationCalibrationPolicyEntity policy,
                                     MdmDurationCalibrationRunEntity run,
                                     List<MesDurationEvidenceDTO.ChangeoverSample> sources) {
        List<ChangePoint> candidates = new ArrayList<>();
        int strategyExcluded = 0;
        for (MesDurationEvidenceDTO.ChangeoverSample source : sources) {
            MdmDurationCalibrationSampleEntity sample = changeoverSample(run, source);
            String mismatch = changeoverMismatch(policy, source);
            if (!Boolean.TRUE.equals(source.getSourceEligible())) {
                sample.setStrategyAccepted(false);
                sample.setExclusionReason(StrUtil.blankToDefault(source.getExclusionReason(), "MES源证据不完整"));
            } else if (mismatch != null) {
                sample.setStrategyAccepted(false);
                sample.setExclusionReason(mismatch);
                strategyExcluded++;
            } else {
                sample.setStrategyAccepted(true);
                sample.setNormalizedSeconds(BigDecimal.valueOf(source.getDurationSeconds()));
                candidates.add(new ChangePoint(source, sample, changeoverGroupKey(policy, source),
                        BigDecimal.valueOf(source.getDurationSeconds())));
            }
            sampleMapper.insert(sample);
        }
        int accepted = 0;
        int proposals = 0;
        Map<String, List<ChangePoint>> groups = candidates.stream()
                .collect(Collectors.groupingBy(ChangePoint::groupKey, LinkedHashMap::new, Collectors.toList()));
        for (List<ChangePoint> group : groups.values()) {
            OutlierResult<ChangePoint> filtered = filterMad(group, ChangePoint::duration, policy);
            markOutliers(filtered.rejected, "换型时长超出MAD阈值");
            strategyExcluded += filtered.rejected.size();
            accepted += filtered.accepted.size();
            MdmDurationCalibrationProposalEntity proposal = changeoverProposal(policy, run, filtered.accepted,
                    group.size() - filtered.accepted.size());
            proposalMapper.insert(proposal);
            for (ChangePoint point : group) {
                point.sample.setProposalId(proposal.getId());
                sampleMapper.updateById(point.sample);
            }
            proposals++;
        }
        return new RunBuild(strategyExcluded, accepted, proposals);
    }

    private MdmDurationCalibrationProposalEntity operationProposal(MdmDurationCalibrationPolicyEntity policy,
                                                                   MdmDurationCalibrationRunEntity run, List<OperationPoint> points, int excluded) {
        OperationPoint first = points.isEmpty() ? null : points.get(0);
        MdmDurationCalibrationProposalEntity row = baseProposal(policy, run, points.size(), excluded,
                first == null ? "UNKNOWN" : first.source.getProcessCode());
        if (first != null) {
            row.setProcessCode(first.source.getProcessCode());
            row.setResourceCode(operationResource(policy.getScopeType(), first.source));
            resolveOperationNames(row);
        }
        MdmProcedureEntity procedure = first == null ? null : findProcedure(first.source.getProcessCode(), policy.getFactoryCode());
        CurrentDuration current = currentDuration(procedure);
        row.setCurrentFixedSeconds(current.fixedSeconds);
        row.setCurrentUnitSeconds(current.unitSeconds);
        Statistics stats = statistics(points.stream().map(point -> BigDecimal.valueOf(point.source.getActiveSeconds())).toList());
        applyStatistics(row, stats);
        Regression regression = regress(points);
        row.setProposedFixedSeconds(regression.intercept.setScale(0, RoundingMode.HALF_UP).longValue());
        row.setProposedUnitSeconds(regression.slope.setScale(6, RoundingMode.HALF_UP));
        row.setRSquared(regression.rSquared.setScale(4, RoundingMode.HALF_UP));
        BigDecimal medianQuantity = median(points.stream().map(point -> point.source.getCompletedQuantity()).toList());
        BigDecimal currentAtMedian = BigDecimal.valueOf(current.fixedSeconds).add(current.unitSeconds.multiply(medianQuantity));
        BigDecimal proposedAtMedian = regression.intercept.add(regression.slope.multiply(medianQuantity));
        row.setDeviationPct(percentDifference(currentAtMedian, proposedAtMedian));
        BigDecimal spread = quantitySpread(points);
        boolean countOk = points.size() >= policy.getMinSampleCount();
        boolean spreadOk = points.stream().map(point -> point.source.getCompletedQuantity()).distinct().count() >= 3
                && spread.compareTo(policy.getMinQuantitySpreadPct()) >= 0;
        boolean coefficientOk = regression.intercept.signum() >= 0 && regression.slope.signum() > 0;
        boolean deviationOk = row.getDeviationPct() == null
                || row.getDeviationPct().compareTo(policy.getMaxDeviationPct()) <= 0;
        boolean eligible = countOk && spreadOk && coefficientOk && deviationOk && procedure != null;
        row.setPublishEligible(eligible);
        row.setEligibilityReason(eligible ? null : joinReasons(
                countOk ? null : "有效样本少于" + policy.getMinSampleCount(),
                spreadOk ? null : "至少需要3种批量且数量跨度达到" + policy.getMinQuantitySpreadPct() + "%",
                coefficientOk ? null : "回归固定项或单位时长不为正",
                deviationOk ? null : "相对当前标准偏差超过策略上限" + policy.getMaxDeviationPct() + "%",
                procedure != null ? null : "工序主数据不存在或未启用"));
        row.setConfidenceLevel(confidence(points.size(), regression.rSquared, stats.cvPct));
        row.setModelSnapshot(JSONUtil.toJsonStr(Map.of(
                "model", "TOTAL_ACTIVE_LINEAR", "quantitySpreadPct", spread,
                "medianQuantity", medianQuantity, "statisticallyEligible", eligible,
                "fixedAllocation", "发布时按当前准备/拆卸比例分配；加工时间按单位秒数乘计划数量")));
        return row;
    }

    private MdmDurationCalibrationProposalEntity changeoverProposal(MdmDurationCalibrationPolicyEntity policy,
                                                                    MdmDurationCalibrationRunEntity run, List<ChangePoint> points, int excluded) {
        ChangePoint first = points.isEmpty() ? null : points.get(0);
        String identity = first == null ? "UNKNOWN" : StrUtil.blankToDefault(first.source.getFromToolingCode(), "EMPTY")
                + "-TO-" + first.source.getToToolingCode();
        MdmDurationCalibrationProposalEntity row = baseProposal(policy, run, points.size(), excluded, identity);
        if (first != null) {
            row.setFromToolingCode(first.source.getFromToolingCode());
            row.setToToolingCode(first.source.getToToolingCode());
            row.setResourceCode(changeoverResource(policy.getScopeType(), first.source));
            resolveChangeoverResourceName(row);
        }
        Statistics stats = statistics(points.stream().map(ChangePoint::duration).toList());
        applyStatistics(row, stats);
        row.setProposedChangeoverSeconds(stats.median.setScale(0, RoundingMode.HALF_UP).longValue());
        row.setCleaningRatioPct(BigDecimal.ZERO);
        boolean countOk = points.size() >= policy.getMinSampleCount();
        row.setPublishEligible(false);
        row.setEligibilityReason(joinReasons(countOk ? null : "有效样本少于" + policy.getMinSampleCount(),
                "换型执行源账只有工装方向，必须人工映射并复核产品族A→B"));
        row.setConfidenceLevel(confidence(points.size(), BigDecimal.ZERO, stats.cvPct));
        row.setModelSnapshot(JSONUtil.toJsonStr(Map.of("model", "ROBUST_MEDIAN", "statisticallyEligible", countOk,
                "mappingRequired", true, "toolingDirection", identity)));
        return row;
    }

    private MdmDurationCalibrationProposalEntity baseProposal(MdmDurationCalibrationPolicyEntity policy,
                                                              MdmDurationCalibrationRunEntity run, int samples, int excluded, String identity) {
        MdmDurationCalibrationProposalEntity row = new MdmDurationCalibrationProposalEntity();
        String baseNo = policy.getPolicyNo() + "-" + sanitize(identity);
        row.setProposalNo(baseNo);
        row.setVersionNo(nextProposalVersion(baseNo, policy.getFactoryCode()));
        row.setRunId(run.getId());
        row.setPolicyId(policy.getId());
        row.setTargetType(policy.getTargetType());
        row.setScopeType(policy.getScopeType());
        row.setSampleCount(samples);
        row.setExcludedSampleCount(excluded);
        row.setEvidenceFingerprint(run.getEvidenceFingerprint());
        row.setStatus("DRAFT");
        row.setEffectiveTime(LocalDateTime.now());
        row.setFactoryCode(policy.getFactoryCode());
        return row;
    }

    private void publish(MdmDurationCalibrationProposalEntity row, MomUser user) {
        LocalDateTime now = LocalDateTime.now();
        if (row.getEffectiveTime() == null) row.setEffectiveTime(now);
        if ("CHANGEOVER".equals(row.getTargetType())) {
            row.setPublishedRuleId(publishChangeoverRule(row, user));
        } else {
            if (!"OPERATION".equals(row.getTargetType())) throw new CheckedException("未知校准目标");
            if (!row.getEffectiveTime().isAfter(now)) retireCurrentOperationProfiles(row, user, "新校准版本生效");
        }
        row.setStatus("ACTIVE");
        row.setApprovedBy(user.getUsername());
        row.setApprovedByName(user.getName());
        row.setApprovedTime(now);
        proposalMapper.updateById(row);
    }

    private Long publishChangeoverRule(MdmDurationCalibrationProposalEntity row, MomUser user) {
        resolveChangeoverTarget(row);
        refreshCurrentChangeover(row);
        LocalDateTime effective = Optional.ofNullable(row.getEffectiveTime()).orElse(LocalDateTime.now());
        List<MdmChangeoverRuleEntity> active = changeoverRuleMapper.selectList(
                new LambdaQueryWrapper<MdmChangeoverRuleEntity>()
                        .eq(MdmChangeoverRuleEntity::getFactoryCode, row.getFactoryCode())
                        .eq(MdmChangeoverRuleEntity::getStatus, "ACTIVE")
                        .eq(MdmChangeoverRuleEntity::getScopeType, row.getScopeType())
                        .eq(MdmChangeoverRuleEntity::getResourceCode, row.getResourceCode())
                        .eq(MdmChangeoverRuleEntity::getFromFamilyCode, row.getFromFamilyCode())
                        .eq(MdmChangeoverRuleEntity::getToFamilyCode, row.getToFamilyCode())
                        .and(w -> w.isNull(MdmChangeoverRuleEntity::getValidEndTime)
                                .or().gt(MdmChangeoverRuleEntity::getValidEndTime, effective)));
        for (MdmChangeoverRuleEntity old : active) {
            if (!Objects.equals(StrUtil.emptyToNull(old.getProcessCode()), StrUtil.emptyToNull(row.getProcessCode())))
                continue;
            if (old.getValidStartTime().isBefore(effective)) {
                old.setValidEndTime(effective);
            } else {
                old.setStatus("RETIRED");
                old.setCloseReason("被校准建议[" + row.getProposalNo() + "]替代");
                old.setClosedBy(user.getUsername());
                old.setClosedByName(user.getName());
                old.setClosedTime(LocalDateTime.now());
            }
            changeoverRuleMapper.updateById(old);
        }
        long totalMinutes = BigDecimal.valueOf(row.getProposedChangeoverSeconds())
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.CEILING).max(BigDecimal.ONE).longValue();
        BigDecimal ratio = Optional.ofNullable(row.getCleaningRatioPct()).orElse(BigDecimal.ZERO)
                .max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
        long cleaning = BigDecimal.valueOf(totalMinutes).multiply(ratio)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
        MdmChangeoverRuleEntity rule = new MdmChangeoverRuleEntity();
        rule.setRuleNo("CAL-" + StrUtil.sub(sanitize(row.getProposalNo()), 0, 70) + "-" + row.getId());
        rule.setRuleName("实际换型校准 " + row.getFromFamilyCode() + "→" + row.getToFamilyCode());
        rule.setVersionNo(row.getVersionNo());
        rule.setScopeType(row.getScopeType());
        rule.setResourceCode(row.getResourceCode());
        rule.setResourceName(row.getResourceName());
        rule.setProcessCode(row.getProcessCode());
        rule.setProcessName(row.getProcessName());
        rule.setFromFamilyCode(row.getFromFamilyCode());
        rule.setFromFamilyName(row.getFromFamilyName());
        rule.setToFamilyCode(row.getToFamilyCode());
        rule.setToFamilyName(row.getToFamilyName());
        rule.setCleaningMinutes(cleaning);
        rule.setSetupMinutes(totalMinutes - cleaning);
        rule.setForbidden(false);
        rule.setPriority(10);
        rule.setValidStartTime(effective);
        rule.setStatus("ACTIVE");
        rule.setApprovedBy(user.getUsername());
        rule.setApprovedByName(user.getName());
        rule.setApprovedTime(LocalDateTime.now());
        rule.setRemark("由实际时长校准建议[" + row.getProposalNo() + "]发布；源指纹=" + row.getEvidenceFingerprint());
        rule.setFactoryCode(row.getFactoryCode());
        rule.setCreateBy(row.getCreateBy());
        changeoverRuleMapper.insert(rule);
        MdmChangeoverRuleLogEntity ruleLog = new MdmChangeoverRuleLogEntity();
        ruleLog.setRuleId(rule.getId());
        ruleLog.setAction("ACTIVATE");
        ruleLog.setFromStatus("DRAFT");
        ruleLog.setToStatus("ACTIVE");
        ruleLog.setReason("由实际换型校准建议[" + row.getProposalNo() + "]批准发布");
        ruleLog.setOperatedBy(user.getUsername());
        ruleLog.setOperatedByName(user.getName());
        ruleLog.setOperatedTime(LocalDateTime.now());
        ruleLog.setBeforeSnapshot(JSONUtil.toJsonStr(row));
        ruleLog.setAfterSnapshot(JSONUtil.toJsonStr(rule));
        ruleLog.setFactoryCode(row.getFactoryCode());
        changeoverRuleLogMapper.insert(ruleLog);
        return rule.getId();
    }

    private void retireCurrentOperationProfiles(MdmDurationCalibrationProposalEntity row, MomUser user, String reason) {
        List<MdmDurationCalibrationProposalEntity> active = proposalMapper.selectList(
                new LambdaQueryWrapper<MdmDurationCalibrationProposalEntity>()
                        .eq(MdmDurationCalibrationProposalEntity::getFactoryCode, row.getFactoryCode())
                        .eq(MdmDurationCalibrationProposalEntity::getTargetType, "OPERATION")
                        .eq(MdmDurationCalibrationProposalEntity::getStatus, "ACTIVE")
                        .eq(MdmDurationCalibrationProposalEntity::getScopeType, row.getScopeType())
                        .eq(MdmDurationCalibrationProposalEntity::getProcessCode, row.getProcessCode()));
        for (MdmDurationCalibrationProposalEntity old : active) {
            if (!Objects.equals(StrUtil.emptyToNull(old.getResourceCode()), StrUtil.emptyToNull(row.getResourceCode())))
                continue;
            old.setStatus("RETIRED");
            old.setCloseReason(reason);
            old.setClosedBy(user.getUsername());
            old.setClosedByName(user.getName());
            old.setClosedTime(LocalDateTime.now());
            old.setSupersededByProposalId(row.getId());
            proposalMapper.updateById(old);
        }
    }

    private void retire(MdmDurationCalibrationProposalEntity row, MomUser user, String reason) {
        row.setStatus("RETIRED");
        row.setCloseReason(trim500(reason));
        row.setClosedBy(user.getUsername());
        row.setClosedByName(user.getName());
        row.setClosedTime(LocalDateTime.now());
        if (row.getPublishedRuleId() != null) {
            MdmChangeoverRuleEntity rule = changeoverRuleMapper.selectById(row.getPublishedRuleId());
            if (rule != null && "ACTIVE".equals(rule.getStatus())) {
                rule.setStatus("RETIRED");
                rule.setCloseReason("校准建议退役：" + trim500(reason));
                rule.setClosedBy(user.getUsername());
                rule.setClosedByName(user.getName());
                rule.setClosedTime(LocalDateTime.now());
                changeoverRuleMapper.updateById(rule);
            }
        }
    }

    private void verifyEvidenceUnchanged(MdmDurationCalibrationProposalEntity proposal) {
        MdmDurationCalibrationRunEntity run = requireRun(proposal.getRunId());
        MdmDurationCalibrationPolicyEntity policy = requirePolicy(run.getPolicyId());
        MesDurationEvidenceDTO.Bundle fresh = fetchEvidence(policy, run.getSampleStartTime(), run.getSampleEndTime());
        if (!Objects.equals(run.getEvidenceFingerprint(), fresh.getEvidenceFingerprint())) {
            throw new CheckedException("MES样本事实已变化，当前建议已陈旧；请重新运行校准并复核新版本");
        }
    }

    private MesDurationEvidenceDTO.Bundle fetchEvidence(MdmDurationCalibrationPolicyEntity policy,
                                                        LocalDateTime from, LocalDateTime to) {
        MesDurationEvidenceDTO.Query query = new MesDurationEvidenceDTO.Query();
        query.setFactoryCode(policy.getFactoryCode());
        query.setFromTime(from);
        query.setToTime(to);
        query.setProcessCode(policy.getProcessCode());
        if ("DEVICE".equals(policy.getScopeType())) query.setDeviceCode(policy.getResourceCode());
        query.setLimit(policy.getMaxSampleCount());
        R<MesDurationEvidenceDTO.Bundle> response = remoteMesTaskService.durationEvidence(query);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("MES实际时长证据读取失败" + (response == null ? "" : "：" + response.getMsg()));
        }
        return response.getData();
    }

    private void refreshPublishEligibility(MdmDurationCalibrationProposalEntity row) {
        MdmDurationCalibrationPolicyEntity policy = requirePolicy(row.getPolicyId());
        boolean statistical = JSONUtil.parseObj(row.getModelSnapshot()).getBool("statisticallyEligible", false);
        List<String> reasons = new ArrayList<>();
        if (!statistical) reasons.add("统计样本未达到策略门槛");
        if ("OPERATION".equals(row.getTargetType())) {
            if (row.getProposedFixedSeconds() == null || row.getProposedFixedSeconds() < 0
                    || row.getProposedUnitSeconds() == null || row.getProposedUnitSeconds().signum() <= 0) {
                reasons.add("固定时长或单位加工时长非法");
            }
        } else {
            if (!CHANGEOVER_SCOPES.contains(row.getScopeType()) || StrUtil.hasBlank(row.getResourceCode(),
                    row.getFromFamilyCode(), row.getToFamilyCode())) reasons.add("资源范围或产品族方向不完整");
            if (row.getProposedChangeoverSeconds() == null || row.getProposedChangeoverSeconds() <= 0)
                reasons.add("建议换型时长必须大于0");
            if (row.getCleaningRatioPct() == null || row.getCleaningRatioPct().signum() < 0
                    || row.getCleaningRatioPct().compareTo(BigDecimal.valueOf(100)) > 0)
                reasons.add("清洁占比必须在0至100之间");
        }
        if (row.getEffectiveTime() == null) reasons.add("生效时间不能为空");
        if (row.getDeviationPct() != null && row.getDeviationPct().compareTo(policy.getMaxDeviationPct()) > 0) {
            reasons.add("相对当前标准偏差超过策略上限" + policy.getMaxDeviationPct() + "%");
        }
        row.setPublishEligible(reasons.isEmpty());
        row.setEligibilityReason(reasons.isEmpty() ? null : String.join("；", reasons));
    }

    private void validateEditableValues(MdmDurationCalibrationProposalEntity row) {
        if (row.getEffectiveTime() == null) throw new CheckedException("建议生效时间不能为空");
        if ("OPERATION".equals(row.getTargetType())) {
            if (row.getProposedFixedSeconds() == null || row.getProposedFixedSeconds() < 0
                    || row.getProposedUnitSeconds() == null || row.getProposedUnitSeconds().signum() <= 0) {
                throw new CheckedException("加工建议的固定秒数不能小于0，单位秒数必须大于0");
            }
        } else if (row.getProposedChangeoverSeconds() == null || row.getProposedChangeoverSeconds() <= 0) {
            throw new CheckedException("建议换型秒数必须大于0");
        }
    }

    private void normalizePolicy(MdmDurationCalibrationPolicyEntity row) {
        row.setPolicyNo(StrUtil.trim(row.getPolicyNo()));
        row.setPolicyName(StrUtil.trim(row.getPolicyName()));
        row.setTargetType(upper(row.getTargetType()));
        row.setScopeType(upper(row.getScopeType()));
        row.setProcessCode(StrUtil.trim(row.getProcessCode()));
        row.setResourceCode(StrUtil.trim(row.getResourceCode()));
        row.setVersionNo(Optional.ofNullable(row.getVersionNo()).orElse(1));
        row.setLookbackDays(Optional.ofNullable(row.getLookbackDays()).orElse(90));
        row.setMinSampleCount(Optional.ofNullable(row.getMinSampleCount()).orElse(8));
        row.setMaxSampleCount(Optional.ofNullable(row.getMaxSampleCount()).orElse(500));
        row.setOutlierMethod(upper(StrUtil.blankToDefault(row.getOutlierMethod(), "MAD_RATE")));
        row.setOutlierThreshold(Optional.ofNullable(row.getOutlierThreshold()).orElse(BigDecimal.valueOf(3.5)));
        row.setMinQuantitySpreadPct(Optional.ofNullable(row.getMinQuantitySpreadPct()).orElse(BigDecimal.valueOf(50)));
        row.setMaxDeviationPct(Optional.ofNullable(row.getMaxDeviationPct()).orElse(BigDecimal.valueOf(50)));
        if (StrUtil.hasBlank(row.getPolicyNo(), row.getPolicyName(), row.getTargetType(), row.getScopeType())) {
            throw new CheckedException("策略编号、名称、目标和范围不能为空");
        }
        if (!TARGETS.contains(row.getTargetType())) throw new CheckedException("校准目标仅支持加工或换型");
        Set<String> scopes = "OPERATION".equals(row.getTargetType()) ? OPERATION_SCOPES : CHANGEOVER_SCOPES;
        if (!scopes.contains(row.getScopeType())) throw new CheckedException("该校准目标不支持所选范围");
        if ("CHANGEOVER".equals(row.getTargetType()) && StrUtil.isNotBlank(row.getProcessCode())) {
            throw new CheckedException("当前换型源账没有权威工序映射，换型策略不能预设工序过滤；建议复核时可补充工序");
        }
        if (row.getVersionNo() <= 0 || row.getLookbackDays() < 1 || row.getLookbackDays() > 366
                || row.getMinSampleCount() < 3 || row.getMaxSampleCount() < row.getMinSampleCount()
                || row.getMaxSampleCount() > 2000 || row.getOutlierThreshold().signum() <= 0
                || row.getMinQuantitySpreadPct().signum() < 0 || row.getMaxDeviationPct().signum() <= 0) {
            throw new CheckedException("策略版本、回看天数、样本量和统计阈值超出允许范围");
        }
        if (!Set.of("MAD_RATE", "NONE").contains(row.getOutlierMethod()))
            throw new CheckedException("异常值方法仅支持MAD_RATE或NONE");
        if (StrUtil.isNotBlank(row.getProcessCode()) && findProcedure(row.getProcessCode(), row.getFactoryCode()) == null) {
            throw new CheckedException("工序[" + row.getProcessCode() + "]不存在或未启用");
        }
        validatePolicyResource(row);
    }

    private void validatePolicyResource(MdmDurationCalibrationPolicyEntity row) {
        if ("PROCESS".equals(row.getScopeType())) {
            row.setResourceCode(null);
            return;
        }
        if (StrUtil.isBlank(row.getResourceCode())) return;
        if ("STATION".equals(row.getScopeType())) {
            MdmStationSettingEntity station = stationMapper.selectOne(new LambdaQueryWrapper<MdmStationSettingEntity>()
                    .eq(MdmStationSettingEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmStationSettingEntity::getStationCode, row.getResourceCode())
                    .eq(MdmStationSettingEntity::getStatus, true).last("limit 1"));
            if (station == null) throw new CheckedException("策略工位[" + row.getResourceCode() + "]不存在或未启用");
        } else if ("DEVICE".equals(row.getScopeType())) {
            MdmDeviceEntity device = deviceMapper.selectOne(new LambdaQueryWrapper<MdmDeviceEntity>()
                    .eq(MdmDeviceEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmDeviceEntity::getDeviceCode, row.getResourceCode())
                    .eq(MdmDeviceEntity::getStatus, true).last("limit 1"));
            if (device == null) throw new CheckedException("策略设备[" + row.getResourceCode() + "]不存在或未启用");
        } else {
            MdmToolingEntity tooling = toolingMapper.selectOne(new LambdaQueryWrapper<MdmToolingEntity>()
                    .eq(MdmToolingEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmToolingEntity::getToolingCode, row.getResourceCode())
                    .eq(MdmToolingEntity::getStatus, true).last("limit 1"));
            if (tooling == null) throw new CheckedException("策略工装[" + row.getResourceCode() + "]不存在或未启用");
        }
    }

    private void resolveOperationNames(MdmDurationCalibrationProposalEntity row) {
        MdmProcedureEntity procedure = findProcedure(row.getProcessCode(), row.getFactoryCode());
        if (procedure != null) row.setProcessName(procedure.getProcessName());
        if ("STATION".equals(row.getScopeType())) {
            MdmStationSettingEntity item = stationMapper.selectOne(new LambdaQueryWrapper<MdmStationSettingEntity>()
                    .eq(MdmStationSettingEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmStationSettingEntity::getStationCode, row.getResourceCode()).last("limit 1"));
            row.setResourceName(item == null ? row.getResourceCode() : item.getStationName());
        } else if ("DEVICE".equals(row.getScopeType())) {
            MdmDeviceEntity item = deviceMapper.selectOne(new LambdaQueryWrapper<MdmDeviceEntity>()
                    .eq(MdmDeviceEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmDeviceEntity::getDeviceCode, row.getResourceCode()).last("limit 1"));
            row.setResourceName(item == null ? row.getResourceCode() : item.getDeviceName());
        }
    }

    private void resolveChangeoverTarget(MdmDurationCalibrationProposalEntity row) {
        if (!CHANGEOVER_SCOPES.contains(row.getScopeType()) || StrUtil.hasBlank(row.getResourceCode(),
                row.getFromFamilyCode(), row.getToFamilyCode()))
            throw new CheckedException("换型建议必须补齐范围资源和产品族方向");
        MdmSchedulingFamilyEntity from = findFamily(row.getFromFamilyCode(), row.getFactoryCode());
        MdmSchedulingFamilyEntity to = findFamily(row.getToFamilyCode(), row.getFactoryCode());
        if (from == null || to == null) throw new CheckedException("换型建议的起止产品族不存在或未启用");
        row.setFromFamilyName(from.getFamilyName());
        row.setToFamilyName(to.getFamilyName());
        if (StrUtil.isNotBlank(row.getProcessCode())) {
            MdmProcedureEntity procedure = findProcedure(row.getProcessCode(), row.getFactoryCode());
            if (procedure == null) throw new CheckedException("工序不存在或未启用");
            row.setProcessName(procedure.getProcessName());
        } else row.setProcessName(null);
        resolveChangeoverResourceName(row);
    }

    private void resolveChangeoverResourceName(MdmDurationCalibrationProposalEntity row) {
        if (StrUtil.isBlank(row.getResourceCode())) return;
        if ("STATION".equals(row.getScopeType())) {
            MdmStationSettingEntity item = stationMapper.selectOne(new LambdaQueryWrapper<MdmStationSettingEntity>()
                    .eq(MdmStationSettingEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmStationSettingEntity::getStationCode, row.getResourceCode()).eq(MdmStationSettingEntity::getStatus, true).last("limit 1"));
            if (item == null) throw new CheckedException("换型工位不存在或未启用");
            row.setResourceName(item.getStationName());
        } else if ("DEVICE".equals(row.getScopeType())) {
            MdmDeviceEntity item = deviceMapper.selectOne(new LambdaQueryWrapper<MdmDeviceEntity>()
                    .eq(MdmDeviceEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmDeviceEntity::getDeviceCode, row.getResourceCode()).eq(MdmDeviceEntity::getStatus, true).last("limit 1"));
            if (item == null) throw new CheckedException("换型设备不存在或未启用");
            row.setResourceName(item.getDeviceName());
        } else {
            MdmToolingEntity item = toolingMapper.selectOne(new LambdaQueryWrapper<MdmToolingEntity>()
                    .eq(MdmToolingEntity::getFactoryCode, row.getFactoryCode())
                    .eq(MdmToolingEntity::getToolingCode, row.getResourceCode()).eq(MdmToolingEntity::getStatus, true).last("limit 1"));
            if (item == null) throw new CheckedException("换型工装不存在或未启用");
            row.setResourceName(item.getToolingName());
        }
    }

    private void refreshCurrentChangeover(MdmDurationCalibrationProposalEntity row) {
        if (StrUtil.hasBlank(row.getScopeType(), row.getResourceCode(), row.getFromFamilyCode(), row.getToFamilyCode()))
            return;
        LocalDateTime effective = Optional.ofNullable(row.getEffectiveTime()).orElse(LocalDateTime.now());
        List<MdmChangeoverRuleEntity> rules = changeoverRuleMapper.selectList(new LambdaQueryWrapper<MdmChangeoverRuleEntity>()
                .eq(MdmChangeoverRuleEntity::getFactoryCode, row.getFactoryCode()).eq(MdmChangeoverRuleEntity::getStatus, "ACTIVE")
                .eq(MdmChangeoverRuleEntity::getScopeType, row.getScopeType()).eq(MdmChangeoverRuleEntity::getResourceCode, row.getResourceCode())
                .eq(MdmChangeoverRuleEntity::getFromFamilyCode, row.getFromFamilyCode())
                .eq(MdmChangeoverRuleEntity::getToFamilyCode, row.getToFamilyCode())
                .le(MdmChangeoverRuleEntity::getValidStartTime, effective)
                .and(w -> w.isNull(MdmChangeoverRuleEntity::getValidEndTime)
                        .or().gt(MdmChangeoverRuleEntity::getValidEndTime, effective))
                .orderByAsc(MdmChangeoverRuleEntity::getPriority));
        MdmChangeoverRuleEntity current = rules.stream().filter(rule -> Objects.equals(StrUtil.emptyToNull(rule.getProcessCode()),
                StrUtil.emptyToNull(row.getProcessCode()))).findFirst().orElse(null);
        row.setCurrentChangeoverSeconds(current == null ? null : current.getTotalMinutes() * 60L);
        row.setDeviationPct(current == null ? null : percentDifference(BigDecimal.valueOf(row.getCurrentChangeoverSeconds()),
                BigDecimal.valueOf(row.getProposedChangeoverSeconds())));
    }

    private String operationMismatch(MdmDurationCalibrationPolicyEntity policy, MesDurationEvidenceDTO.OperationSample s) {
        if (StrUtil.isNotBlank(policy.getProcessCode()) && !Objects.equals(policy.getProcessCode(), s.getProcessCode()))
            return "不匹配策略工序";
        String resource = operationResource(policy.getScopeType(), s);
        if (!"PROCESS".equals(policy.getScopeType()) && StrUtil.isBlank(resource)) return "缺少策略范围资源编码";
        if (StrUtil.isNotBlank(policy.getResourceCode()) && !Objects.equals(policy.getResourceCode(), resource))
            return "不匹配策略资源";
        return null;
    }

    private String changeoverMismatch(MdmDurationCalibrationPolicyEntity policy, MesDurationEvidenceDTO.ChangeoverSample s) {
        String resource = changeoverResource(policy.getScopeType(), s);
        if (StrUtil.isBlank(resource)) return "缺少策略范围资源编码";
        if (StrUtil.isNotBlank(policy.getResourceCode()) && !Objects.equals(policy.getResourceCode(), resource))
            return "不匹配策略资源";
        if (StrUtil.isBlank(s.getToToolingCode())) return "缺少目标工装编码";
        return null;
    }

    private String operationGroupKey(MdmDurationCalibrationPolicyEntity policy, MesDurationEvidenceDTO.OperationSample s) {
        return s.getProcessCode() + "|" + operationResource(policy.getScopeType(), s);
    }

    private String changeoverGroupKey(MdmDurationCalibrationPolicyEntity policy, MesDurationEvidenceDTO.ChangeoverSample s) {
        return changeoverResource(policy.getScopeType(), s) + "|" + StrUtil.blankToDefault(s.getFromToolingCode(), "EMPTY")
                + "|" + s.getToToolingCode();
    }

    private String operationResource(String scope, MesDurationEvidenceDTO.OperationSample s) {
        if ("STATION".equals(scope)) return StrUtil.trim(s.getStationCode());
        if ("DEVICE".equals(scope)) return StrUtil.trim(s.getDeviceCode());
        return "";
    }

    private String changeoverResource(String scope, MesDurationEvidenceDTO.ChangeoverSample s) {
        if ("STATION".equals(scope)) return StrUtil.trim(s.getWorksiteCode());
        if ("DEVICE".equals(scope)) return StrUtil.trim(s.getDeviceCode());
        return StrUtil.trim(s.getToToolingCode());
    }

    private MdmDurationCalibrationSampleEntity operationSample(MdmDurationCalibrationRunEntity run,
                                                               MesDurationEvidenceDTO.OperationSample source) {
        MdmDurationCalibrationSampleEntity row = baseSample(run, "OPERATION", source.getSampleId(),
                source.getSourceFingerprint(), source.getActiveSeconds(), source.getActualEndTime(),
                source.getSourceEligible(), source.getExclusionReason(), JSONUtil.toJsonStr(source));
        row.setProcessCode(source.getProcessCode());
        row.setMaterialCode(source.getMaterialCode());
        row.setStationCode(source.getStationCode());
        row.setDeviceCode(source.getDeviceCode());
        row.setCompletedQuantity(source.getCompletedQuantity());
        return row;
    }

    private MdmDurationCalibrationSampleEntity changeoverSample(MdmDurationCalibrationRunEntity run,
                                                                MesDurationEvidenceDTO.ChangeoverSample source) {
        MdmDurationCalibrationSampleEntity row = baseSample(run, "CHANGEOVER", source.getSampleId(),
                source.getSourceFingerprint(), source.getDurationSeconds(), source.getCompletedTime(),
                source.getSourceEligible(), source.getExclusionReason(), JSONUtil.toJsonStr(source));
        row.setStationCode(source.getWorksiteCode());
        row.setDeviceCode(source.getDeviceCode());
        row.setFromToolingCode(source.getFromToolingCode());
        row.setToToolingCode(source.getToToolingCode());
        return row;
    }

    private MdmDurationCalibrationSampleEntity baseSample(MdmDurationCalibrationRunEntity run, String type,
                                                          String sourceId, String fingerprint, Long duration, LocalDateTime occurred, Boolean eligible,
                                                          String reason, String snapshot) {
        MdmDurationCalibrationSampleEntity row = new MdmDurationCalibrationSampleEntity();
        row.setRunId(run.getId());
        row.setSampleType(type);
        row.setSourceSampleId(sourceId);
        row.setSourceFingerprint(fingerprint);
        row.setDurationSeconds(duration);
        row.setOccurredTime(occurred);
        row.setSourceEligible(eligible);
        row.setExclusionReason(reason);
        row.setSourceSnapshot(snapshot);
        row.setFactoryCode(run.getFactoryCode());
        return row;
    }

    private <T> OutlierResult<T> filterMad(List<T> input, Function<T, BigDecimal> value,
                                           MdmDurationCalibrationPolicyEntity policy) {
        if ("NONE".equals(policy.getOutlierMethod()) || input.size() < 3) return new OutlierResult<>(input, List.of());
        BigDecimal median = median(input.stream().map(value).toList());
        BigDecimal mad = median(input.stream().map(value).map(v -> v.subtract(median).abs()).toList());
        if (mad.signum() == 0) return new OutlierResult<>(input, List.of());
        List<T> accepted = new ArrayList<>();
        List<T> rejected = new ArrayList<>();
        for (T item : input) {
            BigDecimal z = value.apply(item).subtract(median).abs().multiply(BigDecimal.valueOf(0.6745))
                    .divide(mad, 6, RoundingMode.HALF_UP);
            (z.compareTo(policy.getOutlierThreshold()) <= 0 ? accepted : rejected).add(item);
        }
        return new OutlierResult<>(accepted, rejected);
    }

    private void markOutliers(List<? extends SamplePoint> rejected, String reason) {
        for (SamplePoint point : rejected) {
            point.sample().setStrategyAccepted(false);
            point.sample().setExclusionReason(reason);
            sampleMapper.updateById(point.sample());
        }
    }

    private Regression regress(List<OperationPoint> points) {
        if (points.isEmpty()) return new Regression(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal n = BigDecimal.valueOf(points.size());
        BigDecimal sumX = points.stream().map(p -> p.source.getCompletedQuantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumY = points.stream().map(p -> BigDecimal.valueOf(p.source.getActiveSeconds())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal meanX = sumX.divide(n, 12, RoundingMode.HALF_UP);
        BigDecimal meanY = sumY.divide(n, 12, RoundingMode.HALF_UP);
        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal variance = BigDecimal.ZERO;
        for (OperationPoint p : points) {
            BigDecimal dx = p.source.getCompletedQuantity().subtract(meanX);
            BigDecimal dy = BigDecimal.valueOf(p.source.getActiveSeconds()).subtract(meanY);
            covariance = covariance.add(dx.multiply(dy));
            variance = variance.add(dx.multiply(dx));
        }
        if (variance.signum() == 0)
            return new Regression(BigDecimal.ZERO, meanY.divide(meanX.max(BigDecimal.ONE), 12, RoundingMode.HALF_UP), BigDecimal.ZERO);
        BigDecimal slope = covariance.divide(variance, 12, RoundingMode.HALF_UP);
        BigDecimal intercept = meanY.subtract(slope.multiply(meanX));
        BigDecimal ssTotal = BigDecimal.ZERO;
        BigDecimal ssResidual = BigDecimal.ZERO;
        for (OperationPoint p : points) {
            BigDecimal y = BigDecimal.valueOf(p.source.getActiveSeconds());
            BigDecimal predicted = intercept.add(slope.multiply(p.source.getCompletedQuantity()));
            ssTotal = ssTotal.add(y.subtract(meanY).pow(2));
            ssResidual = ssResidual.add(y.subtract(predicted).pow(2));
        }
        BigDecimal r2 = ssTotal.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE.subtract(ssResidual.divide(ssTotal, 12, RoundingMode.HALF_UP));
        return new Regression(intercept, slope, r2.max(BigDecimal.ZERO).min(BigDecimal.ONE));
    }

    private Statistics statistics(List<BigDecimal> values) {
        if (values.isEmpty()) return new Statistics(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        List<BigDecimal> sorted = values.stream().sorted().toList();
        BigDecimal mean = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
        BigDecimal variance = values.stream().map(v -> v.subtract(mean).pow(2)).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 12, RoundingMode.HALF_UP);
        BigDecimal std = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal cv = mean.signum() == 0 ? BigDecimal.ZERO : std.multiply(BigDecimal.valueOf(100)).divide(mean, 4, RoundingMode.HALF_UP);
        int p90Index = Math.max(0, (int) Math.ceil(sorted.size() * 0.9) - 1);
        return new Statistics(mean, median(sorted), sorted.get(p90Index), cv);
    }

    private void applyStatistics(MdmDurationCalibrationProposalEntity row, Statistics stats) {
        row.setMeanSeconds(stats.mean.setScale(2, RoundingMode.HALF_UP));
        row.setMedianSeconds(stats.median.setScale(2, RoundingMode.HALF_UP));
        row.setP90Seconds(stats.p90.setScale(2, RoundingMode.HALF_UP));
        row.setCoefficientVariationPct(stats.cvPct.setScale(2, RoundingMode.HALF_UP));
    }

    private BigDecimal quantitySpread(List<OperationPoint> points) {
        if (points.isEmpty()) return BigDecimal.ZERO;
        BigDecimal min = points.stream().map(p -> p.source.getCompletedQuantity()).min(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal max = points.stream().map(p -> p.source.getCompletedQuantity()).max(BigDecimal::compareTo).orElse(min);
        return min.signum() == 0 ? BigDecimal.ZERO : max.subtract(min).multiply(BigDecimal.valueOf(100)).divide(min, 2, RoundingMode.HALF_UP);
    }

    private CurrentDuration currentDuration(MdmProcedureEntity procedure) {
        if (procedure == null) return new CurrentDuration(0L, BigDecimal.ZERO);
        long fixed = toSeconds(procedure.getSetupTime(), procedure.getSetupTimeUnit())
                + toSeconds(procedure.getDisassemblyTime(), procedure.getDisassemblyTimeUnit());
        long manual = toSeconds(procedure.getManualProcessingTime(), procedure.getManualProcessingTimeUnit());
        long machine = toSeconds(procedure.getMachineProcessingTime(), procedure.getMachineProcessingTimeUnit());
        BigDecimal batch = procedure.getActivityBasicBatch() == null || procedure.getActivityBasicBatch().signum() <= 0
                ? BigDecimal.ONE : procedure.getActivityBasicBatch();
        BigDecimal unit = BigDecimal.valueOf(Math.max(manual, machine)).divide(batch, 6, RoundingMode.HALF_UP);
        return new CurrentDuration(fixed, unit);
    }

    private long toSeconds(BigDecimal value, String unit) {
        if (value == null || value.signum() <= 0) return 0L;
        String normalized = StrUtil.blankToDefault(unit, "MINUTE").toUpperCase(Locale.ROOT);
        BigDecimal factor = normalized.contains("HOUR") || normalized.equals("H") || normalized.contains("小时")
                ? BigDecimal.valueOf(3600) : normalized.contains("DAY") || normalized.equals("D") || normalized.contains("天")
                ? BigDecimal.valueOf(28800) : normalized.contains("SECOND") || normalized.equals("S") || normalized.contains("秒")
                ? BigDecimal.ONE : BigDecimal.valueOf(60);
        return value.multiply(factor).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private String confidence(int count, BigDecimal r2, BigDecimal cv) {
        if (count >= 20 && r2.compareTo(BigDecimal.valueOf(0.8)) >= 0 && cv.compareTo(BigDecimal.valueOf(20)) <= 0)
            return "HIGH";
        if (count >= 8 && cv.compareTo(BigDecimal.valueOf(40)) <= 0) return "MEDIUM";
        return "LOW";
    }

    private BigDecimal percentDifference(BigDecimal current, BigDecimal proposed) {
        if (current == null || current.signum() <= 0 || proposed == null) return null;
        return proposed.subtract(current).abs().multiply(BigDecimal.valueOf(100)).divide(current, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return BigDecimal.ZERO;
        List<BigDecimal> sorted = values.stream().filter(Objects::nonNull).sorted().toList();
        if (sorted.isEmpty()) return BigDecimal.ZERO;
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
    }

    private MdmProcedureEntity findProcedure(String code, String factory) {
        if (StrUtil.isBlank(code)) return null;
        return procedureMapper.selectOne(new LambdaQueryWrapper<MdmProcedureEntity>()
                .eq(MdmProcedureEntity::getFactoryCode, factory).eq(MdmProcedureEntity::getProcessCode, code)
                .eq(MdmProcedureEntity::getStatus, true).last("limit 1"));
    }

    private MdmSchedulingFamilyEntity findFamily(String code, String factory) {
        if (StrUtil.isBlank(code)) return null;
        return familyMapper.selectOne(new LambdaQueryWrapper<MdmSchedulingFamilyEntity>()
                .eq(MdmSchedulingFamilyEntity::getFactoryCode, factory).eq(MdmSchedulingFamilyEntity::getFamilyCode, code)
                .eq(MdmSchedulingFamilyEntity::getEnabled, true).last("limit 1"));
    }

    private MdmDurationCalibrationPolicyEntity requirePolicy(Long id) {
        MdmDurationCalibrationPolicyEntity row = policyMapper.selectById(id);
        if (row == null) throw new CheckedException("校准策略不存在");
        return row;
    }

    private MdmDurationCalibrationRunEntity requireRun(Long id) {
        MdmDurationCalibrationRunEntity row = runMapper.selectById(id);
        if (row == null) throw new CheckedException("校准运行不存在");
        return row;
    }

    private MdmDurationCalibrationProposalEntity requireProposal(Long id) {
        MdmDurationCalibrationProposalEntity row = proposalMapper.selectById(id);
        if (row == null) throw new CheckedException("校准建议不存在");
        return row;
    }

    private void assertPolicyVersionUnique(MdmDurationCalibrationPolicyEntity row) {
        Long count = policyMapper.selectCount(new LambdaQueryWrapper<MdmDurationCalibrationPolicyEntity>()
                .eq(MdmDurationCalibrationPolicyEntity::getFactoryCode, row.getFactoryCode())
                .eq(MdmDurationCalibrationPolicyEntity::getPolicyNo, row.getPolicyNo())
                .eq(MdmDurationCalibrationPolicyEntity::getVersionNo, row.getVersionNo())
                .ne(row.getId() != null, MdmDurationCalibrationPolicyEntity::getId, row.getId()));
        if (count > 0)
            throw new CheckedException("策略[" + row.getPolicyNo() + "]版本V" + row.getVersionNo() + "已存在");
    }

    private int nextProposalVersion(String proposalNo, String factory) {
        return proposalMapper.selectList(new LambdaQueryWrapper<MdmDurationCalibrationProposalEntity>()
                        .eq(MdmDurationCalibrationProposalEntity::getFactoryCode, factory)
                        .eq(MdmDurationCalibrationProposalEntity::getProposalNo, proposalNo))
                .stream().map(MdmDurationCalibrationProposalEntity::getVersionNo).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
    }

    private void clearPolicyLifecycle(MdmDurationCalibrationPolicyEntity row) {
        row.setApprovedBy(null);
        row.setApprovedByName(null);
        row.setApprovedTime(null);
        row.setCloseReason(null);
        row.setClosedBy(null);
        row.setClosedByName(null);
        row.setClosedTime(null);
    }

    private void log(String objectType, Long objectId, String action, String from, String to, String reason,
                     String before, String after, String factory) {
        MomUser user = SecurityUtils.getUser();
        MdmDurationCalibrationLogEntity row = new MdmDurationCalibrationLogEntity();
        row.setObjectType(objectType);
        row.setObjectId(objectId);
        row.setAction(action);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setReason(trim500(reason));
        row.setOperatedBy(user.getUsername());
        row.setOperatedByName(user.getName());
        row.setOperatedTime(LocalDateTime.now());
        row.setBeforeSnapshot(before);
        row.setAfterSnapshot(after);
        row.setFactoryCode(factory);
        logMapper.insert(row);
    }

    private String requireFactory() {
        String factory = StrUtil.trim(FactoryContextHolder.getFactoryCode());
        if (StrUtil.isBlank(factory)) throw new CheckedException("请先选择当前工厂");
        return factory;
    }

    private String profileKey(MdmDurationCalibrationProposalEntity row) {
        return row.getScopeType() + "|" + StrUtil.blankToDefault(row.getResourceCode(), "") + "|" + row.getProcessCode();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成校准目录指纹", ex);
        }
    }

    private String sanitize(String value) {
        return StrUtil.sub(value.replaceAll("[^A-Za-z0-9_-]", "_"), 0, 80);
    }

    private String upper(String value) {
        return StrUtil.blankToDefault(value, "").trim().toUpperCase(Locale.ROOT);
    }

    private String trim500(String value) {
        return StrUtil.isBlank(value) ? null : StrUtil.sub(value.trim(), 0, 500);
    }

    private String joinReasons(String... values) {
        return Arrays.stream(values).filter(StrUtil::isNotBlank).collect(Collectors.joining("；"));
    }

    private <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }

    private interface SamplePoint {
        MdmDurationCalibrationSampleEntity sample();
    }

    private record OperationPoint(MesDurationEvidenceDTO.OperationSample source,
                                  MdmDurationCalibrationSampleEntity sample, String groupKey,
                                  BigDecimal rate) implements SamplePoint {
    }

    private record ChangePoint(MesDurationEvidenceDTO.ChangeoverSample source,
                               MdmDurationCalibrationSampleEntity sample, String groupKey,
                               BigDecimal duration) implements SamplePoint {
    }

    private record OutlierResult<T>(List<T> accepted, List<T> rejected) {
    }

    private record Regression(BigDecimal intercept, BigDecimal slope, BigDecimal rSquared) {
    }

    private record Statistics(BigDecimal mean, BigDecimal median, BigDecimal p90, BigDecimal cvPct) {
    }

    private record CurrentDuration(long fixedSeconds, BigDecimal unitSeconds) {
    }

    private record RunBuild(int strategyExcluded, int accepted, int proposals) {
    }
}
