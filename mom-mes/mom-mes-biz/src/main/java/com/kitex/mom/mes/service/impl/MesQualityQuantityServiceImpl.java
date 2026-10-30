package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesQualityGateDecision;
import com.kitex.mom.mes.dto.MesQualityGateItemDTO;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesApsSourceQuantityService;
import com.kitex.mom.mes.service.MesIdentifierBindingGroupService;
import com.kitex.mom.mes.service.MesQualityQuantityService;
import com.kitex.mom.mes.support.MesQualityEvidenceSupport;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 质量数量台账以报工记录为最小可追溯单元。QMS处置未指定具体SN/批段时，按报工时间FIFO分配
 * 可放行与拒绝数量；已放行数量只增不减，新产生的中检不会反向冻结已被下游使用的历史产出。
 */
@Service
@RequiredArgsConstructor
public class MesQualityQuantityServiceImpl implements MesQualityQuantityService {

    private final MesInspectionEventProjection eventProjection;
    private final MesProductionTaskLock productionTaskLock;
    private final com.kitex.mom.mes.service.MesQualityGateService qualityGateService;
    private final MesTaskReportMapper reportMapper;
    private final MesTaskReportRecordMapper reportRecordMapper;
    private final MesWarehouseLiveStockMapper liveStockMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesProduceTaskMapper produceTaskMapper;
    private final MesTaskMapper taskMapper;
    private final MesApsSourceQuantityService sourceQuantityService;
    private final MesQualityStockProofMapper stockProofMapper;
    private final MesIdentifierBindingGroupService bindingGroups;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesQualityGateDecision reconcile(String produceTaskCode, MesQualityGateDecision decision) {
        return reconcileQuantities(produceTaskCode, decision, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesQualityGateDecision reconcileEvent(String code, Long recordId, Long eventId, java.util.Date time) {
        return reconcileQuantities(code, new MesQualityGateDecision(), null, new EventApplication(recordId, eventId, time));
    }

    private MesQualityGateDecision reconcileQuantities(String produceTaskCode, MesQualityGateDecision decision,
                                                       ReworkQuantities rework) {
        return reconcileQuantities(produceTaskCode, decision, rework, null);
    }

    private MesQualityGateDecision reconcileQuantities(String produceTaskCode, MesQualityGateDecision decision,
                                                       ReworkQuantities rework, EventApplication event) {
        String factory = FactoryContextHolder.getFactoryCode();
        if (TenantContextHolder.getTenantId() == null || TenantContextHolder.getTenantId() <= 0
                || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)
                || StrUtil.isBlank(produceTaskCode) || decision == null) {
            throw new CheckedException("质量数量同步缺少实际租户、工厂、工序或判定依据");
        }
        // 与报工/完工/纠正共用同一工单锁；禁止先锁报告或库存，再反向等待父行。
        QualitySource source = lockSource(produceTaskCode, event != null);
        if (rework == null) eventProjection.lockProcessEvidence(produceTaskCode);
        boolean eventControlled = event != null || (rework == null && eventProjection.controlled(produceTaskCode));
        if (eventControlled) {
            decision = qualityGateService.eventSnapshot(produceTaskCode, event == null ? decision.getPhase() : "ALL",
                    event == null ? null : event.recordId(), event == null ? null : event.eventId(), event == null ? null : event.time());
        }
        boolean reworkOrder = "REWORK".equals(source.task().getResourceType());
        if (rework == null && reworkOrder) {
            throw new CheckedException("返工产出须按已登记的独立复检结果同步，不能使用普通质量放行");
        }
        if (rework != null && (!reworkOrder
                || !Objects.equals(rework.qmsTaskId(), source.task().getReinspectionTaskId())
                || !Boolean.TRUE.equals(source.process().getLastRoute()))) {
            throw new CheckedException("返工复检与已登记任务或最终工序不一致");
        }
        List<MesTaskReportEntity> reports = reportMapper.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getProduceTaskCode, produceTaskCode)
                .and(w -> w.isNull(MesTaskReportEntity::getStatus)
                        .or().eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()))
                .ne(MesTaskReportEntity::getQualityQuantityStatus, QualityQuantityStatusEnum.NOT_REQUIRED.name())
                .orderByAsc(MesTaskReportEntity::getId).last("LIMIT 5001 FOR UPDATE"));
        if (reports.size() > 5000) throw new CheckedException("质量同步报工超过5000条，请先治理工序数据，禁止截断放行");
        reports.sort(Comparator.comparing(MesTaskReportEntity::getReportTime,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(MesTaskReportEntity::getId));
        Map<String, MesTaskArrangeEntity> arrangements = requireReportSources(reports, source);
        if (rework != null) requireReworkQuantities(rework, source, reports, arrangements);
        if (reports.isEmpty()) return fillTotals(decision, reports);

        BigDecimal alreadyReleased = sum(reports, QuantityPart.RELEASED);
        BigDecimal alreadyRejected = sum(reports, QuantityPart.REJECTED);
        BigDecimal pending = sum(reports, QuantityPart.PENDING);
        BigDecimal releaseCapacity;
        BigDecimal rejectCapacity;
        if (rework != null) {
            releaseCapacity = rework.usable();
            rejectCapacity = rework.rejected();
        } else if (Boolean.TRUE.equals(decision.getPassed())) {
            releaseCapacity = alreadyReleased.add(pending);
            rejectCapacity = alreadyRejected;
        } else {
            releaseCapacity = acceptedCapacity(decision.getItems());
            rejectCapacity = rejectedCapacity(decision.getItems());
        }
        BigDecimal releaseNeed = positive(releaseCapacity.subtract(alreadyReleased));
        if (rework == null) releaseNeed = releaseNeed.min(pending);
        BigDecimal rejectNeed = positive(rejectCapacity.subtract(alreadyRejected));
        MesQualityEvidenceSupport.Evidence qualityEvidence = MesQualityEvidenceSupport.capture(decision);

        for (MesTaskReportEntity report : reports) {
            BigDecimal reportPending = nz(report.getQualityPendingNum());
            if (reportPending.signum() <= 0) {
                continue;
            }
            BigDecimal release = reportPending.min(releaseNeed);
            if (release.signum() > 0) {
                transitionStock(report, release, QualityQuantityStatusEnum.RELEASED, decision);
                reportPending = reportPending.subtract(release);
                report.setQualityReleasedNum(nz(report.getQualityReleasedNum()).add(release));
                releaseNeed = releaseNeed.subtract(release);
                applyReleasedQuantity(report, release, source, arrangements.get(report.getArrangeCode()));
            }
            BigDecimal reject = reportPending.min(rejectNeed);
            if (reject.signum() > 0) {
                transitionStock(report, reject, QualityQuantityStatusEnum.REJECTED, decision);
                reportPending = reportPending.subtract(reject);
                report.setQualityRejectedNum(nz(report.getQualityRejectedNum()).add(reject));
                rejectNeed = rejectNeed.subtract(reject);
                MesTaskEntity task = source.task();
                if (!"REWORK".equals(task.getResourceType())
                        && (task.getPlanNo() != null || task.getApsBatchNo() != null)) {
                    if (eventControlled) sourceQuantityService.recordDeferred(task,
                            "QUALITY-REJECTED:" + report.getId() + ":"
                                    + report.getQualityRejectedNum().stripTrailingZeros().toPlainString(),
                            MesSourceQuantityKind.QUALITY_REJECTED, reject, String.valueOf(report.getId()),
                            "报工记录【" + report.getId() + "】QMS累计判退【" + report.getQualityRejectedNum()
                                    + "】，本次判退【" + reject + "】");
                    else sourceQuantityService.record(task,
                            "QUALITY-REJECTED:" + report.getId() + ":"
                                    + report.getQualityRejectedNum().stripTrailingZeros().toPlainString(),
                            MesSourceQuantityKind.QUALITY_REJECTED, reject, String.valueOf(report.getId()),
                            "报工记录【" + report.getId() + "】QMS累计判退【" + report.getQualityRejectedNum()
                                    + "】，本次判退【" + reject + "】");
                }
            }
            if (release.signum() > 0 || reject.signum() > 0) {
                report.setQualityPendingNum(reportPending);
                report.setQualityQuantityStatus(deriveStatus(report));
                report.setQualitySyncTime(LocalDateTime.now());
                report.setQualityTaskIds(qualityEvidence.taskIdsJson());
                report.setQualityEvidenceFingerprint(qualityEvidence.fingerprint());
                report.setQualityEvidenceSnapshot(qualityEvidence.snapshotJson());
                if (reportMapper.updateById(report) != 1) throw new CheckedException("质量数量报工更新失败，已回滚");
                int expectedMirrors = 0;
                if (eventControlled) {
                    var mirrors = reportRecordMapper.selectList(new LambdaQueryWrapper<MesTaskReportRecordEntity>()
                            .eq(MesTaskReportRecordEntity::getTenantId, report.getTenantId())
                            .eq(MesTaskReportRecordEntity::getFactoryCode, report.getFactoryCode())
                            .eq(MesTaskReportRecordEntity::getReportId, report.getId())
                            .eq(MesTaskReportRecordEntity::getType, ReportRecordTypeEnum.REPORT.name())
                            .orderByAsc(MesTaskReportRecordEntity::getId).last("LIMIT 5001 FOR UPDATE"));
                    expectedMirrors = mirrors.size();
                    if (expectedMirrors == 0 || expectedMirrors > 5000)
                        throw new CheckedException("正式质量应用缺少有效报工明细或明细超限，已整体回滚");
                }
                int updatedMirrors = reportRecordMapper.update(null, new LambdaUpdateWrapper<MesTaskReportRecordEntity>()
                        .eq(MesTaskReportRecordEntity::getTenantId, report.getTenantId())
                        .eq(MesTaskReportRecordEntity::getFactoryCode, report.getFactoryCode())
                        .eq(MesTaskReportRecordEntity::getReportId, report.getId())
                        .eq(MesTaskReportRecordEntity::getType, ReportRecordTypeEnum.REPORT.name())
                        .set(MesTaskReportRecordEntity::getQualityQuantityStatus, report.getQualityQuantityStatus())
                        .set(MesTaskReportRecordEntity::getQualityTaskIds, report.getQualityTaskIds())
                        .set(MesTaskReportRecordEntity::getQualityEvidenceFingerprint,
                                report.getQualityEvidenceFingerprint())
                        .set(MesTaskReportRecordEntity::getQualityEvidenceSnapshot,
                                report.getQualityEvidenceSnapshot())
                        .set(MesTaskReportRecordEntity::getQualityReleasedNum, report.getQualityReleasedNum())
                        .set(MesTaskReportRecordEntity::getQualityPendingNum, report.getQualityPendingNum())
                        .set(MesTaskReportRecordEntity::getQualityRejectedNum, report.getQualityRejectedNum()));
                if (eventControlled && updatedMirrors != expectedMirrors)
                    throw new CheckedException("正式质量报工明细更新不完整，已整体回滚");
                saveAudit(report, release, reject);
            }
        }
        if (rework != null && (releaseNeed.signum() > 0 || rejectNeed.signum() > 0)) {
            throw new CheckedException("返工复检处置数量未完整落账，已整体回滚");
        }
        return fillTotals(decision, reportMapper.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getTaskCode, source.task().getCode())
                .eq(MesTaskReportEntity::getProduceTaskCode, produceTaskCode)
                .ne(rework != null, MesTaskReportEntity::getQualityQuantityStatus, QualityQuantityStatusEnum.NOT_REQUIRED.name())
                .and(w -> w.isNull(MesTaskReportEntity::getStatus)
                        .or().eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()))));
    }

    private QualitySource lockSource(String code) {
        return lockSource(code, false);
    }

    private QualitySource lockSource(String code, boolean internal) {
        MesProduceTaskEntity observed = uniqueProcess(code, false);
        if (StrUtil.isBlank(observed.getTaskCode())) throw new CheckedException("质量工序缺少原生产工单");
        if (internal) productionTaskLock.lock(observed.getTaskCode());
        else bindingGroups.lockDispatchTask(observed.getTaskCode());
        var roots = taskMapper.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MesTaskEntity::getCode, observed.getTaskCode()).last("LIMIT 2 FOR UPDATE"));
        if (roots.size() != 1) throw new CheckedException("质量同步缺少唯一原生产工单");
        MesProduceTaskEntity locked = uniqueProcess(code, true);
        if (!Objects.equals(observed.getId(), locked.getId())
                || !Objects.equals(observed.getTaskCode(), locked.getTaskCode())) {
            throw new CheckedException("质量工序归属已变化，请重新同步");
        }
        return new QualitySource(roots.get(0), locked);
    }

    private MesProduceTaskEntity uniqueProcess(String code, boolean lock) {
        var rows = produceTaskMapper.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MesProduceTaskEntity::getCode, code).last(lock ? "LIMIT 2 FOR UPDATE" : "LIMIT 2"));
        if (rows.size() != 1) throw new CheckedException("质量工序任务单号不存在或重复，请核对原工序任务");
        return rows.get(0);
    }

    private Map<String, MesTaskArrangeEntity> requireReportSources(List<MesTaskReportEntity> reports,
                                                                   QualitySource source) {
        Map<String, MesTaskArrangeEntity> arrangements = new HashMap<>();
        for (MesTaskReportEntity report : reports) {
            if (report.getId() == null || !Objects.equals(report.getTenantId(), source.task().getTenantId())
                    || !Objects.equals(report.getFactoryCode(), source.task().getFactoryCode())
                    || !Objects.equals(report.getTaskCode(), source.task().getCode())
                    || !Objects.equals(report.getProduceTaskCode(), source.process().getCode())
                    || StrUtil.isBlank(report.getArrangeCode())) {
                throw new CheckedException("质量报工与原工单或工序归属不一致");
            }
            if (!arrangements.containsKey(report.getArrangeCode())) {
                var rows = arrangeMapper.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                        .eq(MesTaskArrangeEntity::getTenantId, report.getTenantId())
                        .eq(MesTaskArrangeEntity::getFactoryCode, report.getFactoryCode())
                        .eq(MesTaskArrangeEntity::getCode, report.getArrangeCode()).last("LIMIT 2 FOR UPDATE"));
                if (rows.size() != 1) throw new CheckedException("质量报工缺少唯一原派工");
                MesFrozenExecutionGraph.assertArrangement(source.process(), rows.get(0));
                arrangements.put(report.getArrangeCode(), rows.get(0));
            }
        }
        return arrangements;
    }

    /**
     * 返工处置按累计数量核定，不能把合格布尔值扩成全部未检产出的放行。
     */
    private void requireReworkQuantities(ReworkQuantities requested, QualitySource source,
                                         List<MesTaskReportEntity> reports,
                                         Map<String, MesTaskArrangeEntity> arrangements) {
        BigDecimal reported = BigDecimal.ZERO;
        BigDecimal released = BigDecimal.ZERO;
        BigDecimal rejected = BigDecimal.ZERO;
        Map<String, BigDecimal> releasedByArrange = new HashMap<>();
        for (MesTaskReportEntity report : reports) {
            BigDecimal total = report.getReportNum();
            BigDecimal good = nz(report.getQualityReleasedNum());
            BigDecimal bad = nz(report.getQualityRejectedNum());
            BigDecimal pending = nz(report.getQualityPendingNum());
            if (total == null || total.signum() < 0 || good.signum() < 0 || bad.signum() < 0
                    || pending.signum() < 0 || total.compareTo(good.add(bad).add(pending)) != 0) {
                throw new CheckedException("返工报工质量数量缺失或不守恒，请核对原报工");
            }
            reported = reported.add(total);
            released = released.add(good);
            rejected = rejected.add(bad);
            releasedByArrange.merge(report.getArrangeCode(), good, BigDecimal::add);
        }
        if (reported.compareTo(nz(source.process().getCompleteNum())) != 0
                || released.compareTo(nz(source.process().getQualityReleasedNum())) != 0) {
            throw new CheckedException("返工实际完成量或质量量与原报工不一致，不能推断复检数量");
        }
        for (var entry : arrangements.entrySet()) {
            if (nz(entry.getValue().getQualityReleasedNum())
                    .compareTo(releasedByArrange.get(entry.getKey())) != 0) {
                throw new CheckedException("返工派工质量量与原报工不一致，请先核对数量");
            }
        }
        if (requested.handled().compareTo(reported) > 0 || requested.usable().compareTo(released) < 0
                || requested.rejected().compareTo(rejected) < 0) {
            throw new CheckedException("复检处置超出实际产出或累计数量倒退，须核对原结果或走受控逆向");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesQualityGateDecision reconcileRework(String produceTaskCode, Long qmsTaskId,
                                                  BigDecimal usableNum, BigDecimal handledNum,
                                                  boolean allQualified) {
        BigDecimal usable = nz(usableNum);
        BigDecimal handled = nz(handledNum);
        if (qmsTaskId == null || qmsTaskId <= 0 || usable.signum() < 0 || handled.signum() < 0
                || usable.compareTo(handled) > 0 || (allQualified && usable.compareTo(handled) != 0)) {
            throw new CheckedException("返工复检数量关系不正确");
        }
        MesQualityGateItemDTO item = new MesQualityGateItemDTO();
        item.setInspectionId(qmsTaskId);
        item.setProduceTaskCode(produceTaskCode);
        item.setGateStatus(allQualified ? "PASSED" : "PENDING");
        try {
            item.setQmsUsableNum(usable.intValueExact());
            item.setQmsHandleNum(handled.intValueExact());
        } catch (ArithmeticException invalidCount) {
            throw new CheckedException("返工复检数量须为QMS支持的非负整数，不能四舍五入", invalidCount);
        }
        MesQualityGateDecision decision = new MesQualityGateDecision()
                .setAllowed(Boolean.TRUE)
                .setPassed(allQualified)
                .setGateMode("BLOCK")
                .setMessage("返工复检已按累计处置数量同步")
                .setItems(List.of(item));
        return reconcileQuantities(produceTaskCode, decision, new ReworkQuantities(qmsTaskId, usable, handled));
    }

    private BigDecimal acceptedCapacity(List<MesQualityGateItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream().map(item -> BigDecimal.valueOf(Objects.requireNonNullElse(item.getQmsUsableNum(), 0)))
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal rejectedCapacity(List<MesQualityGateItemDTO> items) {
        if (items == null) {
            return BigDecimal.ZERO;
        }
        return items.stream().map(item -> BigDecimal.valueOf(Math.max(0,
                        Objects.requireNonNullElse(item.getQmsHandleNum(), 0)
                                - Objects.requireNonNullElse(item.getQmsUsableNum(), 0))))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private void transitionStock(MesTaskReportEntity report, BigDecimal quantity,
                                 QualityQuantityStatusEnum target, MesQualityGateDecision decision) {
        BigDecimal remaining = quantity;
        List<MesWarehouseLiveStockEntity> stocks = liveStockMapper.selectList(
                new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                        .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                        .eq(MesWarehouseLiveStockEntity::getTenantId, report.getTenantId())
                        .eq(MesWarehouseLiveStockEntity::getFactoryCode, report.getFactoryCode())
                        .eq(MesWarehouseLiveStockEntity::getSourceReportId, report.getId())
                        .in(MesWarehouseLiveStockEntity::getInspectionStatus,
                                QualityQuantityStatusEnum.PENDING.name(), QualityQuantityStatusEnum.PARTIAL.name())
                        .orderByAsc(MesWarehouseLiveStockEntity::getId).last("LIMIT 5001 FOR UPDATE"));
        if (stocks.size() > 5000) throw new CheckedException("单次质量库存候选超过5000条，禁止截断处置");
        for (MesWarehouseLiveStockEntity stock : stocks) {
            if (stock.getNum() == null || stock.getNum().signum() < 0)
                throw new CheckedException("待判库存存在空或负数量，请先核对真实库存");
        }
        stocks.sort(Comparator.comparing(MesWarehouseLiveStockEntity::getInTime,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(MesWarehouseLiveStockEntity::getId));
        for (MesWarehouseLiveStockEntity stock : stocks) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal stockNum = nz(stock.getNum());
            if (stockNum.signum() == 0) continue;
            BigDecimal move = stockNum.min(remaining);
            Long resultStockId = stock.getId();
            boolean splitRow = move.compareTo(stockNum) < 0;
            String lockStatus = target == QualityQuantityStatusEnum.RELEASED
                    ? YesNoEnum.NO.getCode() : YesNoEnum.YES.getCode();
            if (!splitRow) {
                int updated = liveStockMapper.update(null, new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                        .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                        .eq(BaseEntity::getId, stock.getId()).eq(MesWarehouseLiveStockEntity::getNum, stockNum)
                        .eq(MesWarehouseLiveStockEntity::getInspectionStatus, stock.getInspectionStatus())
                        .set(MesWarehouseLiveStockEntity::getInspectionStatus, target.name())
                        .set(MesWarehouseLiveStockEntity::getStatus, lockStatus));
                if (updated != 1) {
                    throw new CheckedException("质量库存已被其他业务更新，请重新同步");
                }
            } else {
                int updated = liveStockMapper.update(null, new LambdaUpdateWrapper<MesWarehouseLiveStockEntity>()
                        .isNull(MesWarehouseLiveStockEntity::getQualityCaseId)
                        .eq(BaseEntity::getId, stock.getId()).eq(MesWarehouseLiveStockEntity::getNum, stockNum)
                        .eq(MesWarehouseLiveStockEntity::getInspectionStatus, stock.getInspectionStatus())
                        .set(MesWarehouseLiveStockEntity::getNum, stockNum.subtract(move)));
                if (updated != 1) {
                    throw new CheckedException("质量库存已被其他业务更新，请重新同步");
                }
                MesWarehouseLiveStockEntity split = new MesWarehouseLiveStockEntity();
                // 业务来源与入库时间继承，建账人/建账时间属于本次拆分。
                BeanUtils.copyProperties(stock, split, "id", "createTime", "createBy", "updateTime", "updateBy");
                split.setNum(move);
                split.setInspectionStatus(target.name());
                split.setStatus(lockStatus);
                if (liveStockMapper.insert(split) != 1) throw new CheckedException("质量拆分库存保存失败，已回滚");
                resultStockId = split.getId();
            }
            BigDecimal sourceAfter = splitRow ? stockNum.subtract(move) : stockNum;
            MesWarehouseLiveStockEntity persistedSource = liveStockMapper.selectById(stock.getId());
            MesWarehouseLiveStockEntity persistedResult = splitRow ? liveStockMapper.selectById(resultStockId) : persistedSource;
            if (persistedSource == null || persistedResult == null || persistedSource.getNum() == null
                    || persistedResult.getNum() == null || persistedSource.getNum().compareTo(sourceAfter) != 0
                    || persistedResult.getNum().compareTo(move) != 0
                    || !target.name().equals(persistedResult.getInspectionStatus())
                    || !lockStatus.equals(persistedResult.getStatus())) {
                throw new CheckedException("质量库存拆分数量精度或状态不一致，已整体回滚");
            }
            MesQualityStockProofEntity proof = new MesQualityStockProofEntity();
            proof.setTenantId(report.getTenantId());
            proof.setFactoryCode(report.getFactoryCode());
            proof.setProofVersion("QUALITY_STOCK_V1");
            proof.setProduceTaskCode(report.getProduceTaskCode());
            proof.setSourceReportId(report.getId());
            proof.setSourceStockId(stock.getId());
            proof.setResultStockId(resultStockId);
            proof.setOperationType(splitRow ? "SPLIT" : "STATUS");
            proof.setMatCode(stock.getMatCode());
            proof.setMatBatch(stock.getMatBatch());
            proof.setMatBarcode(stock.getMatBarcode());
            proof.setMatUnit(stock.getMatUnit());
            proof.setQuantity(move);
            proof.setSourceBeforeNum(stockNum);
            proof.setSourceAfterNum(sourceAfter);
            proof.setResultAfterNum(move);
            proof.setBeforeInspectionStatus(stock.getInspectionStatus());
            proof.setAfterInspectionStatus(target.name());
            proof.setBeforeLockStatus(stock.getStatus());
            proof.setAfterLockStatus(lockStatus);
            proof.setDecisionSnapshot(JSONUtil.toJsonStr(decision));
            proof.setSourceSnapshot(JSONUtil.toJsonStr(stock));
            if (stockProofMapper.insert(proof) != 1) throw new CheckedException("质量库存证据保存失败，已整体回滚");
            remaining = remaining.subtract(move);
        }
        if (remaining.signum() > 0) {
            throw new CheckedException("报工质量库存数量不足，可能已被人工修改，请先核对库存流水");
        }
    }

    private void applyReleasedQuantity(MesTaskReportEntity report, BigDecimal delta, QualitySource source,
                                       MesTaskArrangeEntity arrange) {
        int arrangeUpdated = arrangeMapper.update(null, new LambdaUpdateWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getId, arrange.getId())
                .eq(MesTaskArrangeEntity::getTenantId, report.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, report.getFactoryCode())
                .eq(MesTaskArrangeEntity::getTaskCode, report.getTaskCode())
                .eq(MesTaskArrangeEntity::getProduceTaskCode, report.getProduceTaskCode())
                .eq(MesTaskArrangeEntity::getCode, report.getArrangeCode())
                .setSql("quality_released_num = COALESCE(quality_released_num, 0) + {0}", delta));
        if (arrangeUpdated != 1) throw new CheckedException("质量放行缺少唯一原派工，已回滚库存和数量");
        MesProduceTaskEntity produceTask = source.process();
        BigDecimal before = nz(produceTask.getQualityReleasedNum());
        BigDecimal after = before.add(delta);
        LambdaUpdateWrapper<MesProduceTaskEntity> update = new LambdaUpdateWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getId, produceTask.getId())
                .eq(MesProduceTaskEntity::getTenantId, report.getTenantId())
                .eq(MesProduceTaskEntity::getFactoryCode, report.getFactoryCode())
                .eq(MesProduceTaskEntity::getTaskCode, report.getTaskCode())
                .eq(MesProduceTaskEntity::getCode, report.getProduceTaskCode())
                .set(MesProduceTaskEntity::getQualityReleasedNum, after);
        if (produceTask.getQualityReleasedNum() == null) update.isNull(MesProduceTaskEntity::getQualityReleasedNum);
        else update.eq(MesProduceTaskEntity::getQualityReleasedNum, before);
        boolean overlapReached = Boolean.TRUE.equals(produceTask.getOverlapEnabled())
                && produceTask.getTransferBatchQuantity() != null
                && before.compareTo(produceTask.getTransferBatchQuantity()) < 0
                && after.compareTo(produceTask.getTransferBatchQuantity()) >= 0;
        boolean finishReleased = MesProduceTaskStatusEnum.FINISH.name().equals(produceTask.getStatus())
                && after.compareTo(nz(produceTask.getCompleteNum())) >= 0;
        if ((overlapReached || finishReleased) && produceTask.getTransferReleasedTime() == null) {
            LocalDateTime releaseTime = LocalDateTime.now().plusMinutes(
                    Objects.requireNonNullElse(produceTask.getTransferMinutes(), 0L));
            update.set(MesProduceTaskEntity::getTransferReleasedTime, releaseTime);
            produceTask.setTransferReleasedTime(releaseTime);
        }
        if (produceTaskMapper.update(null, update) != 1) {
            throw new CheckedException("工序质量放行数量已被其他业务更新，请重新同步");
        }
        produceTask.setQualityReleasedNum(after);
        if (Boolean.TRUE.equals(arrange.getLastRoute())) {
            int taskUpdated = taskMapper.update(null, new LambdaUpdateWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getId, source.task().getId())
                    .eq(MesTaskEntity::getTenantId, report.getTenantId())
                    .eq(MesTaskEntity::getFactoryCode, report.getFactoryCode())
                    .eq(MesTaskEntity::getCode, report.getTaskCode())
                    .setSql("un_in_stock_num = COALESCE(un_in_stock_num, 0) + {0}", delta));
            if (taskUpdated != 1) throw new CheckedException("末工序质量放行缺少唯一原工单，已回滚");
        }
    }

    private void saveAudit(MesTaskReportEntity report, BigDecimal released, BigDecimal rejected) {
        MesTaskReportRecordEntity record = new MesTaskReportRecordEntity();
        BeanUtils.copyProperties(report, record, "id", "createTime", "createBy", "updateTime", "updateBy");
        record.setReportId(report.getId()).setType(ReportRecordTypeEnum.QUALITY_SYNC.name())
                .setContent("质量数量同步：本次放行" + released.toPlainString() + "，本次拒绝"
                        + rejected.toPlainString() + "；累计放行" + nz(report.getQualityReleasedNum()).toPlainString()
                        + "，待判" + nz(report.getQualityPendingNum()).toPlainString()
                        + "，拒绝" + nz(report.getQualityRejectedNum()).toPlainString());
        if (reportRecordMapper.insert(record) != 1) throw new CheckedException("质量同步审计保存失败，已回滚");
    }

    private MesQualityGateDecision fillTotals(MesQualityGateDecision decision, List<MesTaskReportEntity> reports) {
        return decision.setReportedQuantity(reports.stream().map(MesTaskReportEntity::getReportNum)
                        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add))
                .setReleasedQuantity(sum(reports, QuantityPart.RELEASED))
                .setPendingQuantity(sum(reports, QuantityPart.PENDING))
                .setRejectedQuantity(sum(reports, QuantityPart.REJECTED));
    }

    private BigDecimal sum(List<MesTaskReportEntity> reports, QuantityPart part) {
        return reports.stream().map(report -> switch (part) {
            case RELEASED -> report.getQualityReleasedNum();
            case PENDING -> report.getQualityPendingNum();
            case REJECTED -> report.getQualityRejectedNum();
        }).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String deriveStatus(MesTaskReportEntity report) {
        if (nz(report.getQualityPendingNum()).signum() > 0) {
            return nz(report.getQualityReleasedNum()).signum() > 0 || nz(report.getQualityRejectedNum()).signum() > 0
                    ? QualityQuantityStatusEnum.PARTIAL.name() : QualityQuantityStatusEnum.PENDING.name();
        }
        if (nz(report.getQualityRejectedNum()).signum() > 0) {
            return nz(report.getQualityReleasedNum()).signum() > 0
                    ? QualityQuantityStatusEnum.PARTIAL.name() : QualityQuantityStatusEnum.REJECTED.name();
        }
        return QualityQuantityStatusEnum.RELEASED.name();
    }

    private BigDecimal positive(BigDecimal value) {
        return value.signum() > 0 ? value : BigDecimal.ZERO;
    }

    private BigDecimal nz(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }

    private enum QuantityPart {RELEASED, PENDING, REJECTED}

    private record EventApplication(Long recordId, Long eventId, java.util.Date time) {
    }

    private record QualitySource(MesTaskEntity task, MesProduceTaskEntity process) {
    }

    private record ReworkQuantities(Long qmsTaskId, BigDecimal usable, BigDecimal handled) {
        BigDecimal rejected() {
            return handled.subtract(usable);
        }
    }
}
