package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestCreateCommand;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestContextDTO;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestProof;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestReviewCommand;
import com.kitex.mom.mes.entity.MesChangePointEffectRetestEntity;
import com.kitex.mom.mes.entity.MesChangePointImprovementEntity;
import com.kitex.mom.mes.mapper.MesChangePointEffectRetestMapper;
import com.kitex.mom.mes.mapper.MesChangePointImprovementMapper;
import com.kitex.mom.mes.service.MesChangePointEffectRetestService;
import com.kitex.mom.mes.service.MesChangePointImprovementService;
import com.kitex.mom.qms.api.feign.RemoteQmsCapaService;
import com.kitex.mom.qms.dto.QmsCapaLinkDTO;
import com.kitex.mom.qms.dto.QmsCapaMesRetestReopenCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 变化点损失改善的关闭后复测闭环。
 *
 * <p>固定来源口径和目标，计算/证据不可改写；独立复核只确认或退回，不提供自由选择PASS。
 * FAIL先提交MES事实，再由QMS反查并开启新周期。远端响应丢失不会撤销MES复核或改换请求号。</p>
 */
@Service
@RequiredArgsConstructor
public class MesChangePointEffectRetestServiceImpl
        extends ServiceImpl<MesChangePointEffectRetestMapper, MesChangePointEffectRetestEntity>
        implements MesChangePointEffectRetestService {

    private static final Set<String> METRICS = Set.of("EVENT_DAILY", "LOSS_DAILY", "PAUSE_FAILURE_RATE");
    private static final Set<String> CHECKS = Set.of("SCOPE_APPLICABLE", "SAMPLES_COMPARABLE",
            "CRITERIA_ALIGNED", "EVIDENCE_CHECKED");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final MesActualFactoryAccessService factoryAccess;
    private final MesChangePointImprovementService improvementService;
    private final MesChangePointImprovementMapper improvementMapper;
    private final MesChangePointEffectEvidenceReader evidenceReader;
    private final RemoteQmsCapaService qmsService;
    private final MesChangePointImprovementServiceImpl improvementOperations;
    private final MesChangePointImprovementCoordinator improvementCoordinator;
    private final PlatformTransactionManager transactionManager;

    @Override
    public MesChangePointEffectRetestContextDTO context(Long improvementId) {
        MesChangePointImprovementEntity source = improvementService.getDetail(improvementId);
        QmsCapaLinkDTO link = readLink(source);
        return new MesChangePointEffectRetestContextDTO().setSource(source).setCapaStatus(link.getStatus())
                .setCycleNo(link.getCycleNo()).setClosedTime(link.getClosedTime())
                .setEffectivenessCriteria(link.getEffectivenessCriteria());
    }

    @Override
    public Page<MesChangePointEffectRetestEntity> queryPage(Long improvementId, long current, long size) {
        improvementService.getDetail(improvementId);
        require(current > 0 && size > 0 && size <= 100, "复测页码须大于0，每页最多100条");
        return page(new Page<>(current, size), scope().eq(MesChangePointEffectRetestEntity::getImprovementId, improvementId)
                .select(MesChangePointEffectRetestEntity.class, field -> !Set.of("evidenceSnapshot", "capaSnapshot")
                        .contains(field.getProperty()))
                .orderByDesc(MesChangePointEffectRetestEntity::getCreateTime)
                .orderByDesc(MesChangePointEffectRetestEntity::getId));
    }

    @Override
    public MesChangePointEffectRetestEntity detail(Long id) {
        return requireRecord(id, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public MesChangePointEffectRetestEntity create(MesChangePointEffectRetestCreateCommand command) {
        validateCreate(command);
        String factory = factoryAccess.require("write");
        String requestFingerprint = MesChangePointEffectEvidenceReader.hash(JSONUtil.toJsonStr(Arrays.asList(
                command.getImprovementId(), command.getWindowStartTime().toString(), command.getWindowEndTime().toString(),
                command.getMetric(), command.getRequiredReductionPercent().stripTrailingZeros().toPlainString(),
                command.getMinimumReportSamples(), command.getReopenDueDays())));
        // 同一改善来源串行建账，防止双击/多账号并发对同一窗口登记不同目标。
        MesChangePointImprovementEntity source = improvementMapper.selectOne(
                new LambdaQueryWrapper<MesChangePointImprovementEntity>()
                        .eq(MesChangePointImprovementEntity::getId, command.getImprovementId())
                        .eq(MesChangePointImprovementEntity::getTenantId, TenantContextHolder.getTenantId())
                        .eq(MesChangePointImprovementEntity::getFactoryCode, factory).last("FOR UPDATE"));
        require(source != null, "当前租户、工厂未找到改善来源单");
        MesChangePointEffectRetestEntity replay = getOne(scope().eq(MesChangePointEffectRetestEntity::getRequestId,
                command.getRequestId()));
        if (replay != null) {
            require(Objects.equals(requestFingerprint, replay.getRequestFingerprint()), "复测请求号已使用但内容不同");
            return replay;
        }
        require(StrUtil.isNotBlank(source.getBaselineEventSnapshot()),
                "历史来源缺少发起时原始证据快照，不能补造效果基线；请从新权威基线发起改善");
        QmsCapaLinkDTO link = readLink(source);
        require("CLOSED".equals(link.getStatus()) && StrUtil.isNotBlank(link.getClosedTimeExact()) && link.getCycleNo() != null,
                "只有QMS权威确认关闭的CAPA周期允许效果复测");
        LocalDateTime closedTime = LocalDateTime.parse(link.getClosedTimeExact());
        require(!command.getWindowStartTime().isBefore(closedTime)
                        && !command.getWindowStartTime().isBefore(source.getBaselineEndTime()),
                "复测窗口不得与原基线重叠或早于CAPA关闭");
        MesChangePointEffectRetestEntity sameWindow = getOne(scope()
                .eq(MesChangePointEffectRetestEntity::getImprovementId, source.getId())
                .eq(MesChangePointEffectRetestEntity::getCapaCycleNo, link.getCycleNo())
                .eq(MesChangePointEffectRetestEntity::getWindowStartTime, command.getWindowStartTime())
                .eq(MesChangePointEffectRetestEntity::getWindowEndTime, command.getWindowEndTime())
                .notIn(MesChangePointEffectRetestEntity::getStatus, "REJECTED", "STALE"));
        if (sameWindow != null) {
            require(Objects.equals(requestFingerprint, sameWindow.getRequestFingerprint()),
                    "该周期、窗口已有其他复测口径或目标，不能通过重复申请选择有利结果");
            return sameWindow;
        }
        var observation = evidenceReader.observe(source, command.getWindowStartTime(), command.getWindowEndTime(),
                command.getMinimumReportSamples());
        List<String> gaps = new ArrayList<>(observation.gaps());
        if (Duration.between(command.getWindowStartTime(), command.getWindowEndTime()).compareTo(
                Duration.between(source.getBaselineStartTime(), source.getBaselineEndTime())) < 0) {
            gaps.add("复测观察时长短于原基线，不能形成通过结论");
        }
        BigDecimal before = metric(source.getBaselineEventSnapshot(), command.getMetric(),
                source.getBaselineStartTime(), source.getBaselineEndTime(), gaps, "基线");
        BigDecimal after = metric(observation.snapshot(), command.getMetric(),
                command.getWindowStartTime(), command.getWindowEndTime(), gaps, "复测");
        if (before == null || before.signum() <= 0) {
            gaps.add("基线指标缺失或为零，下降目标不可计算");
        }
        BigDecimal target = before == null ? null : before.multiply(BigDecimal.ONE.subtract(
                command.getRequiredReductionPercent().divide(HUNDRED))).setScale(8, RoundingMode.HALF_UP);
        // 显示值可以舍入，判定必须使用原始分子/分母交叉相乘，不能因8位舍入把超标变成通过。
        String verdict = !gaps.isEmpty() || after == null ? "INSUFFICIENT"
                : reachesTarget(source, observation.snapshot(), command) ? "PASS" : "FAIL";
        String retestNo = "CPRET-" + IdUtil.getSnowflakeNextIdStr();
        MesChangePointEffectRetestEntity record = new MesChangePointEffectRetestEntity()
                .setRetestNo(retestNo).setRequestId(command.getRequestId()).setRequestFingerprint(requestFingerprint)
                .setImprovementId(source.getId()).setImprovementNo(source.getImprovementNo())
                .setQmsCapaId(link.getCapaId()).setCapaCycleNo(link.getCycleNo()).setCapaClosedTime(closedTime)
                .setCapaFingerprint(link.getLifecycleFingerprint()).setCapaSnapshot(JSONUtil.toJsonStr(link))
                .setWindowStartTime(command.getWindowStartTime()).setWindowEndTime(command.getWindowEndTime())
                .setMetric(command.getMetric()).setRequiredReductionPercent(command.getRequiredReductionPercent())
                .setMinimumReportSamples(command.getMinimumReportSamples()).setReopenDueDays(command.getReopenDueDays())
                .setBeforeValue(before).setAfterValue(after).setTargetValue(target)
                .setActualReductionPercent(before != null && before.signum() > 0 && after != null
                        ? before.subtract(after).multiply(HUNDRED).divide(before, 8, RoundingMode.HALF_UP) : null)
                .setObservedEventCount(observation.events()).setUnclearedEventCount(observation.unclearedEvents())
                .setReportSampleCount(observation.reportSamples()).setEvidenceSnapshot(observation.snapshot())
                .setEvidenceFingerprint(observation.fingerprint()).setCalculatedResult(verdict)
                .setGaps(String.join("；", gaps)).setStatus("PENDING")
                .setReopenRequestId("MES-CP-REOPEN-" + retestNo).setReopenStatus("NOT_REQUIRED").setFactoryCode(factory);
        record.setTenantId(source.getTenantId());
        record.setCreateBy(SecurityUtils.getUser().getUsername());
        record.setCreateTime(LocalDateTime.now());
        require(save(record), "效果复测申请保存失败");
        audit(source, command.getRequestId(), "RETEST_CREATE", retestNo + "，计算=" + verdict
                + "，证据=" + observation.fingerprint());
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public MesChangePointEffectRetestEntity review(MesChangePointEffectRetestReviewCommand command) {
        require(command != null && command.getId() != null, "复测申请不能为空");
        text(command.getRequestId(), 100, "复核请求号");
        text(command.getRemark(), 2000, "独立复核说明");
        require(command.getDecision() != null && Set.of("APPROVE", "REJECT").contains(command.getDecision()), "复核动作无效");
        require(command.getChecks() == null || command.getChecks().stream().allMatch(Objects::nonNull), "复核检查项不能为空");
        String checks = JSONUtil.toJsonStr(command.getChecks() == null ? List.of()
                : command.getChecks().stream().distinct().sorted().toList());
        MesChangePointEffectRetestEntity record = requireRecord(command.getId(), true);
        String operator = SecurityUtils.getUser().getUsername();
        if (record.getReviewRequestId() != null) {
            boolean decisionMatches = "REJECT".equals(command.getDecision()) ? "REJECTED".equals(record.getStatus())
                    : Set.of("PASS", "FAIL", "INSUFFICIENT", "STALE").contains(record.getStatus());
            require(Objects.equals(record.getReviewRequestId(), command.getRequestId()) && decisionMatches
                            && Objects.equals(record.getReviewedBy(), operator)
                            && Objects.equals(record.getReviewRemark(), command.getRemark().trim())
                            && Objects.equals(record.getReviewChecks(), checks), "复测已核验或复核请求内容不同");
            return record;
        }
        require("PENDING".equals(record.getStatus()), "只有待独立复核的复测可操作");
        require(!Objects.equals(operator, record.getCreateBy()), "复测申请人与独立复核人必须分离");
        MesChangePointImprovementEntity source = improvementService.getDetail(record.getImprovementId());
        if ("REJECT".equals(command.getDecision())) {
            record.setStatus("REJECTED");
        } else {
            require(command.getChecks() != null && new HashSet<>(command.getChecks()).equals(CHECKS)
                    && command.getChecks().size() == CHECKS.size(), "须完成四项同口径及证据核验，不能新增任意检查项");
            QmsCapaLinkDTO link = readLink(source);
            require(link.getExcludedReviewerCodes() != null && !link.getExcludedReviewerCodes().contains(operator),
                    "CAPA协调人或该周期措施责任人不能独立复核改善效果");
            var latest = evidenceReader.observe(source, record.getWindowStartTime(), record.getWindowEndTime(),
                    record.getMinimumReportSamples());
            boolean unchanged = "CLOSED".equals(link.getStatus())
                    && Objects.equals(record.getCapaFingerprint(), link.getLifecycleFingerprint())
                    && Objects.equals(record.getEvidenceFingerprint(), latest.fingerprint());
            record.setStatus(unchanged ? record.getCalculatedResult() : "STALE");
            if ("FAIL".equals(record.getStatus())) {
                record.setReopenStatus("PENDING");
            }
        }
        record.setReviewRequestId(command.getRequestId()).setReviewedBy(operator)
                .setReviewedByName(StrUtil.blankToDefault(SecurityUtils.getUser().getName(), operator))
                .setReviewedTime(LocalDateTime.now()).setReviewRemark(command.getRemark().trim()).setReviewChecks(checks);
        require(updateById(record), "复测独立复核保存失败");
        audit(source, command.getRequestId(), "RETEST_REVIEW", record.getRetestNo() + "，结论="
                + record.getStatus() + "，" + record.getReviewRemark());
        return record;
    }

    @Override
    public MesChangePointEffectRetestProof proof(Long id) {
        MesChangePointEffectRetestEntity record = requireRecord(id, false);
        require("FAIL".equals(record.getStatus()) && record.getReviewedTime() != null,
                "只有已独立复核的未达标事实可用于QMS复开");
        return new MesChangePointEffectRetestProof().setRetestId(record.getId()).setRetestNo(record.getRetestNo())
                .setImprovementId(record.getImprovementId()).setImprovementNo(record.getImprovementNo())
                .setCapaId(record.getQmsCapaId()).setClosedCycleNo(record.getCapaCycleNo())
                .setCapaFingerprint(record.getCapaFingerprint()).setReopenRequestId(record.getReopenRequestId())
                .setEvidenceFingerprint(record.getEvidenceFingerprint()).setStatus(record.getStatus())
                .setReviewedBy(record.getReviewedBy()).setCollectedBy(record.getCreateBy())
                .setReopenDueDays(record.getReopenDueDays()).setReviewRemark(record.getReviewRemark())
                .setFactoryCode(record.getFactoryCode()).setTenantId(record.getTenantId());
    }

    @Override
    public void executeReopen(Long id) {
        MesChangePointEffectRetestEntity record = requireRecord(id, false);
        require("FAIL".equals(record.getStatus()), "只有独立批准的未达标复测可以复开CAPA");
        if ("APPLIED".equals(record.getReopenStatus())) {
            return;
        }
        try {
            var response = qmsService.reopenFromMesRetest(new QmsCapaMesRetestReopenCommand()
                    .setCapaId(record.getQmsCapaId()).setRetestId(record.getId()).setRequestId(record.getReopenRequestId()));
            require(response != null && response.isOk() && response.getData() != null, "QMS复开结果不明，请按原请求号重试");
            QmsCapaLinkDTO link = response.getData();
            require(Objects.equals(link.getCapaId(), record.getQmsCapaId())
                    && Objects.equals(link.getTenantId(), record.getTenantId())
                    && Objects.equals(link.getFactoryCode(), record.getFactoryCode())
                    && Objects.equals(link.getSourceId(), record.getImprovementId())
                    && Objects.equals(link.getSourceNo(), record.getImprovementNo())
                    && Objects.equals(link.getReopenRequestId(), record.getReopenRequestId())
                    && Objects.equals(link.getReopenRetestId(), record.getId())
                    && Objects.equals(link.getReopenedCycleNo(), record.getCapaCycleNo() + 1), "QMS复开回执来源或周期不一致");
            persistReopen(record.getId(), "APPLIED", "QMS已按原号开启第" + link.getReopenedCycleNo() + "周期");
        } catch (Exception exception) {
            persistReopen(record.getId(), "FAILED_UNKNOWN", exception instanceof CheckedException
                    ? StrUtil.subPre(exception.getMessage(), 450) : "QMS复开失败或结果不明，保留已批准事实，请原号续投");
            return;
        }
        // 回执已独立持久化；后续阶段刷新失败不会把APPLIED退回不明，也不会重复开启周期。
        try {
            improvementCoordinator.refresh(record.getImprovementId());
        } catch (Exception ignored) {
            // 来源链接工作台仍可单独刷新，永久复开回执不受影响。
        }
    }

    private void persistReopen(Long id, String status, String result) {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            MesChangePointEffectRetestEntity record = requireRecord(id, true);
            if ("APPLIED".equals(record.getReopenStatus())) {
                return;
            }
            require("FAIL".equals(record.getStatus()), "复开回执只能关联已批准失败事实");
            record.setReopenStatus(status).setReopenResult(result).setReopenTime(LocalDateTime.now());
            require(updateById(record), "QMS复开回执保存失败");
            audit(improvementService.getDetail(record.getImprovementId()), IdUtil.fastSimpleUUID(),
                    "RETEST_REOPEN", record.getRetestNo() + "，" + status + "，" + result);
        });
    }

    private BigDecimal metric(String snapshot, String metric, LocalDateTime start, LocalDateTime end,
                              List<String> gaps, String phase) {
        var facts = JSONUtil.parseObj(snapshot);
        if ("LOSS_DAILY".equals(metric) && facts.getLong("unclearedEventCount", 0L) > 0) {
            gaps.add(phase + "仍有未核清事件，确认损失不能代表完整损失");
        }
        if ("LOSS_DAILY".equals(metric) && (facts.getLong("missingLossCount", 0L) > 0
                || facts.getJSONArray("unclassifiedEvents") != null && !facts.getJSONArray("unclassifiedEvents").isEmpty())) {
            gaps.add(phase + "存在未确认损失金额或未分类事件，不把缺数视为零");
        }
        if ("PAUSE_FAILURE_RATE".equals(metric)) {
            if (facts.getLong("pauseUnknownCount", 0L) > 0) {
                gaps.add(phase + "存在自动暂停结果未确定的事件，失败率不能假定为零");
            }
            long denominator = facts.getLong("autoPauseCount", 0L);
            if (denominator == 0) {
                gaps.add(phase + "没有自动暂停暴露样本，暂停失败率保持未知");
                return null;
            }
            return BigDecimal.valueOf(facts.getLong("pauseFailureCount", 0L)).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
        }
        BigDecimal numerator = "EVENT_DAILY".equals(metric) ? BigDecimal.valueOf(facts.getLong("eventCount", 0L))
                : new BigDecimal(facts.getStr("lossAmount", "0"));
        return numerator.multiply(new BigDecimal("86400")).divide(
                seconds(start, end), 8, RoundingMode.HALF_UP);
    }

    /** 对同一指标使用精确有理数判定，目标百分比最多两位小数。 */
    private boolean reachesTarget(MesChangePointImprovementEntity source, String afterSnapshot,
                                  MesChangePointEffectRetestCreateCommand command) {
        var before = JSONUtil.parseObj(source.getBaselineEventSnapshot());
        var after = JSONUtil.parseObj(afterSnapshot);
        boolean pause = "PAUSE_FAILURE_RATE".equals(command.getMetric());
        String key = pause ? "pauseFailureCount" : "EVENT_DAILY".equals(command.getMetric()) ? "eventCount" : "lossAmount";
        BigDecimal beforeNumerator = new BigDecimal(before.getStr(key));
        BigDecimal afterNumerator = new BigDecimal(after.getStr(key));
        BigDecimal beforeDenominator = pause ? new BigDecimal(before.getStr("autoPauseCount"))
                : seconds(source.getBaselineStartTime(), source.getBaselineEndTime());
        BigDecimal afterDenominator = pause ? new BigDecimal(after.getStr("autoPauseCount"))
                : seconds(command.getWindowStartTime(), command.getWindowEndTime());
        return afterNumerator.multiply(beforeDenominator).multiply(HUNDRED).compareTo(
                beforeNumerator.multiply(afterDenominator).multiply(HUNDRED.subtract(command.getRequiredReductionPercent()))) <= 0;
    }

    private BigDecimal seconds(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private QmsCapaLinkDTO readLink(MesChangePointImprovementEntity source) {
        require(source.getQmsCapaId() != null, "来源尚未取得QMS CAPA，请先完成可靠链接");
        var response = qmsService.getLink(source.getQmsCapaId());
        require(response != null && response.isOk() && response.getData() != null, "QMS CAPA状态不可核验");
        QmsCapaLinkDTO link = response.getData();
        require(Objects.equals(link.getCapaId(), source.getQmsCapaId())
                && Objects.equals(link.getTenantId(), source.getTenantId())
                && Objects.equals(link.getFactoryCode(), source.getFactoryCode())
                && "MES_CHANGE_POINT_PARETO".equals(link.getSourceType())
                && Objects.equals(link.getSourceId(), source.getId())
                && Objects.equals(link.getSourceNo(), source.getImprovementNo())
                && StrUtil.isNotBlank(link.getLifecycleFingerprint()), "QMS CAPA来源、租户、工厂或关闭依据不一致");
        return link;
    }

    private LambdaQueryWrapper<MesChangePointEffectRetestEntity> scope() {
        String factory = factoryAccess.require("read");
        return new LambdaQueryWrapper<MesChangePointEffectRetestEntity>()
                .eq(MesChangePointEffectRetestEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesChangePointEffectRetestEntity::getFactoryCode, factory);
    }

    private MesChangePointEffectRetestEntity requireRecord(Long id, boolean lock) {
        require(id != null, "复测主键不能为空");
        LambdaQueryWrapper<MesChangePointEffectRetestEntity> query = scope().eq(MesChangePointEffectRetestEntity::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        MesChangePointEffectRetestEntity record = getOne(query);
        require(record != null, "当前租户、工厂未找到复测申请");
        return record;
    }

    private void audit(MesChangePointImprovementEntity source, String requestId, String action, String content) {
        improvementOperations.saveOperation(source, requestId, action, source.getLinkStatus(), source.getLinkStatus(), content);
    }

    private void validateCreate(MesChangePointEffectRetestCreateCommand command) {
        require(command != null && command.getImprovementId() != null, "复测来源不能为空");
        text(command.getRequestId(), 100, "复测请求号");
        require(command.getMetric() != null && METRICS.contains(command.getMetric()), "复测指标无效");
        require(command.getWindowStartTime() != null && command.getWindowEndTime() != null
                        && command.getWindowEndTime().isAfter(command.getWindowStartTime())
                        && !command.getWindowEndTime().isAfter(LocalDateTime.now()), "复测须使用已结束且开始早于结束的窗口");
        Duration duration = Duration.between(command.getWindowStartTime(), command.getWindowEndTime());
        require(duration.compareTo(Duration.ofMinutes(1)) >= 0 && duration.compareTo(Duration.ofDays(366)) <= 0,
                "复测窗口须为1分钟至366天");
        BigDecimal reduction = command.getRequiredReductionPercent();
        require(reduction != null && reduction.signum() >= 0 && reduction.compareTo(HUNDRED) <= 0
                && reduction.stripTrailingZeros().scale() <= 2, "下降目标须为0至100，最多2位小数");
        require(command.getMinimumReportSamples() != null && command.getMinimumReportSamples() > 0
                && command.getMinimumReportSamples() <= 10000, "每产品有效报工样本要求须为1至10000");
        require(command.getReopenDueDays() != null && command.getReopenDueDays() > 0
                && command.getReopenDueDays() <= 365, "复开周期期限须为1至365天");
    }

    private void text(String value, int length, String label) {
        require(StrUtil.isNotBlank(value) && value.trim().length() <= length, label + "不能为空或长度超限");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }
}
