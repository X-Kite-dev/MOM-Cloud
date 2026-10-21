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
import com.kitex.mom.mes.service.MesQualityQuantityService;
import com.kitex.mom.mes.support.MesQualityEvidenceSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 质量数量台账以报工记录为最小可追溯单元。QMS处置未指定具体SN/批段时，按报工时间FIFO分配
 * 可放行与拒绝数量；已放行数量只增不减，新产生的中检不会反向冻结已被下游使用的历史产出。
 */
@Service
@RequiredArgsConstructor
public class MesQualityQuantityServiceImpl implements MesQualityQuantityService {

    private final MesTaskReportMapper reportMapper;
    private final MesTaskReportRecordMapper reportRecordMapper;
    private final MesWarehouseLiveStockMapper liveStockMapper;
    private final MesTaskArrangeMapper arrangeMapper;
    private final MesProduceTaskMapper produceTaskMapper;
    private final MesTaskMapper taskMapper;
    private final MesApsSourceQuantityService sourceQuantityService;
    private final MesQualityStockProofMapper stockProofMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesQualityGateDecision reconcile(String produceTaskCode, MesQualityGateDecision decision) {
        String factory = FactoryContextHolder.getFactoryCode();
        if (TenantContextHolder.getTenantId() == null || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)
                || StrUtil.isBlank(produceTaskCode) || decision == null) {
            throw new CheckedException("质量数量同步缺少实际租户、工厂、工序或判定依据");
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
        if (reports.isEmpty()) {
            return fillTotals(decision, reports);
        }

        BigDecimal alreadyReleased = sum(reports, QuantityPart.RELEASED);
        BigDecimal alreadyRejected = sum(reports, QuantityPart.REJECTED);
        BigDecimal pending = sum(reports, QuantityPart.PENDING);
        BigDecimal releaseCapacity = Boolean.TRUE.equals(decision.getPassed())
                ? alreadyReleased.add(pending) : acceptedCapacity(decision.getItems());
        BigDecimal rejectCapacity = Boolean.TRUE.equals(decision.getPassed())
                ? alreadyRejected : rejectedCapacity(decision.getItems());
        BigDecimal releaseNeed = positive(releaseCapacity.subtract(alreadyReleased)).min(pending);
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
                applyReleasedQuantity(report, release);
            }
            BigDecimal reject = reportPending.min(rejectNeed);
            if (reject.signum() > 0) {
                transitionStock(report, reject, QualityQuantityStatusEnum.REJECTED, decision);
                reportPending = reportPending.subtract(reject);
                report.setQualityRejectedNum(nz(report.getQualityRejectedNum()).add(reject));
                rejectNeed = rejectNeed.subtract(reject);
                MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getCode, report.getTaskCode()));
                if (task == null) throw new CheckedException("质量判退报工缺少生产工单，不能归集来源计划");
                if (!"REWORK".equals(task.getResourceType())
                        && (task.getPlanNo() != null || task.getApsBatchNo() != null)) {
                    sourceQuantityService.record(task,
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
                reportRecordMapper.update(null, new LambdaUpdateWrapper<MesTaskReportRecordEntity>()
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
                saveAudit(report, release, reject);
            }
        }
        return fillTotals(decision, reportMapper.selectList(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getProduceTaskCode, produceTaskCode)
                .and(w -> w.isNull(MesTaskReportEntity::getStatus)
                        .or().eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()))));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MesQualityGateDecision reconcileRework(String produceTaskCode, Long qmsTaskId,
                                                  BigDecimal usableNum, BigDecimal handledNum,
                                                  boolean allQualified) {
        BigDecimal usable = nz(usableNum);
        BigDecimal handled = nz(handledNum);
        if (usable.signum() < 0 || handled.signum() < 0 || usable.compareTo(handled) > 0) {
            throw new CheckedException("返工复检数量关系不正确");
        }
        MesQualityGateItemDTO item = new MesQualityGateItemDTO();
        item.setInspectionId(qmsTaskId);
        item.setProduceTaskCode(produceTaskCode);
        item.setGateStatus(allQualified ? "PASSED" : "PENDING");
        item.setQmsUsableNum(usable.intValueExact());
        item.setQmsHandleNum(handled.intValueExact());
        MesQualityGateDecision decision = new MesQualityGateDecision()
                .setAllowed(Boolean.TRUE)
                .setPassed(allQualified)
                .setGateMode("BLOCK")
                .setMessage(allQualified ? "返工复检合格，产出已解除隔离" : "返工复检数量处置同步")
                .setItems(List.of(item));
        return reconcile(produceTaskCode, decision);
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

    private void applyReleasedQuantity(MesTaskReportEntity report, BigDecimal delta) {
        int arrangeUpdated = arrangeMapper.update(null, new LambdaUpdateWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getFactoryCode, report.getFactoryCode())
                .eq(MesTaskArrangeEntity::getCode, report.getArrangeCode())
                .setSql("quality_released_num = COALESCE(quality_released_num, 0) + " + delta.toPlainString()));
        if (arrangeUpdated != 1) throw new CheckedException("质量放行缺少唯一原派工，已回滚库存和数量");
        MesProduceTaskEntity produceTask = produceTaskMapper.selectOne(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getCode, report.getProduceTaskCode()));
        if (produceTask == null) {
            throw new CheckedException("报工对应工序任务不存在，不能同步质量数量");
        }
        BigDecimal before = nz(produceTask.getQualityReleasedNum());
        BigDecimal after = before.add(delta);
        LambdaUpdateWrapper<MesProduceTaskEntity> update = new LambdaUpdateWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getId, produceTask.getId())
                .eq(MesProduceTaskEntity::getQualityReleasedNum, before)
                .set(MesProduceTaskEntity::getQualityReleasedNum, after);
        boolean overlapReached = Boolean.TRUE.equals(produceTask.getOverlapEnabled())
                && produceTask.getTransferBatchQuantity() != null
                && before.compareTo(produceTask.getTransferBatchQuantity()) < 0
                && after.compareTo(produceTask.getTransferBatchQuantity()) >= 0;
        boolean finishReleased = MesProduceTaskStatusEnum.FINISH.name().equals(produceTask.getStatus())
                && after.compareTo(nz(produceTask.getCompleteNum())) >= 0;
        if ((overlapReached || finishReleased) && produceTask.getTransferReleasedTime() == null) {
            update.set(MesProduceTaskEntity::getTransferReleasedTime, LocalDateTime.now().plusMinutes(
                    Objects.requireNonNullElse(produceTask.getTransferMinutes(), 0L)));
        }
        if (produceTaskMapper.update(null, update) != 1) {
            throw new CheckedException("工序质量放行数量已被其他业务更新，请重新同步");
        }
        MesTaskArrangeEntity arrange = arrangeMapper.selectOne(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getCode, report.getArrangeCode()));
        if (arrange != null && Boolean.TRUE.equals(arrange.getLastRoute())) {
            int taskUpdated = taskMapper.update(null, new LambdaUpdateWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getFactoryCode, report.getFactoryCode())
                    .eq(MesTaskEntity::getCode, report.getTaskCode())
                    .setSql("un_in_stock_num = COALESCE(un_in_stock_num, 0) + " + delta.toPlainString()));
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
}
