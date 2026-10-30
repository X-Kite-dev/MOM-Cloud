package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.agent.dto.QmsBatchRecallStatusDTO;
import com.kitex.mom.qms.entity.QmsBatchRecallEntity;
import com.kitex.mom.qms.mapper.QmsBatchRecallMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 面向制造Agent的批次召回治理只读查询。
 *
 * <p>召回状态来自QMS正式主单；本服务不重算处置结论、不调用写接口，也不将历史已关闭
 * 记录解释为当前影响范围仍然有效。当前范围是否漂移由上层使用实时MES/QMS证据摘要比对。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsBatchRecallStatusQueryService {

    public static final String TOOL_CODE = "QMS_BATCH_RECALL_STATUS_QUERY@1";

    private static final Set<String> TERMINAL_STATUSES = Set.of("CLOSED", "REJECTED");

    private final QmsAgentFactoryAccessService factoryAccess;
    private final QmsBatchRecallMapper recallMapper;

    /**
     * 返回当前工厂、物料和批次完全匹配的正式召回单摘要。
     */
    public QmsBatchRecallStatusDTO.Evidence query(QmsBatchRecallStatusDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getMaterialCode())
                || !StringUtils.hasText(query.getBatchNo())) {
            throw new CheckedException("批次召回状态查询必须提供完整物料编码和批次号");
        }
        String factory = factoryAccess.requireRead(query.getFactoryCode());
        String material = query.getMaterialCode().trim();
        String batch = query.getBatchNo().trim();
        int limit = query.getDetailLimit() == null ? 10 : Math.max(1, Math.min(query.getDetailLimit(), 20));
        LambdaQueryWrapper<QmsBatchRecallEntity> scope = scope(factory, material, batch);
        long total = recallMapper.selectCount(scope);
        long closed = recallMapper.selectCount(scope(factory, material, batch)
                .eq(QmsBatchRecallEntity::getStatus, "CLOSED"));
        long open = recallMapper.selectCount(scope(factory, material, batch)
                .notIn(QmsBatchRecallEntity::getStatus, TERMINAL_STATUSES));
        List<QmsBatchRecallStatusDTO.RecallRecord> records = recallMapper.selectList(
                        scope(factory, material, batch)
                                .orderByDesc(QmsBatchRecallEntity::getCreateTime)
                                .orderByDesc(QmsBatchRecallEntity::getId)
                                .last("LIMIT " + limit))
                .stream().map(this::record).toList();
        return new QmsBatchRecallStatusDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("按当前工厂、物料编码和批次号精确读取QMS正式召回治理单；历史关闭只代表关闭时通过验证，仍须与当前跨域范围摘要比对。")
                .setFactoryCode(factory).setMaterialCode(material).setBatchNo(batch)
                .setObservedAt(LocalDateTime.now()).setRecallRecordFound(total > 0)
                .setOpenRecallCount(open).setClosedRecallCount(closed)
                .setLatestRecall(records.isEmpty() ? null : records.get(0))
                .setReturnedRecordCount(records.size()).setRecords(records)
                .setEvidenceRefs(records.stream().map(item -> "QMS:qms_batch_recall:" + item.getRecallId()).toList())
                .setTruncated(total > records.size());
    }

    private LambdaQueryWrapper<QmsBatchRecallEntity> scope(String factory, String material, String batch) {
        return new LambdaQueryWrapper<QmsBatchRecallEntity>()
                .eq(QmsBatchRecallEntity::getFactoryCode, factory)
                .eq(QmsBatchRecallEntity::getMaterialCode, material)
                .eq(QmsBatchRecallEntity::getBatchNo, batch);
    }

    private QmsBatchRecallStatusDTO.RecallRecord record(QmsBatchRecallEntity item) {
        return new QmsBatchRecallStatusDTO.RecallRecord()
                .setRecallId(item.getId()).setRecallNo(item.getRecallNo())
                .setSeverity(item.getSeverity()).setStatus(item.getStatus()).setReason(item.getReason())
                .setEvidenceHash(item.getEvidenceHash()).setDownstreamScopeHash(item.getDownstreamScopeHash())
                .setObservedAt(item.getObservedAt()).setMesMatchedReportCount(item.getMesMatchedReportCount())
                .setWmsPositiveStockRowCount(item.getWmsPositiveStockRowCount())
                .setQmsOpenNcrCount(item.getQmsOpenNcrCount())
                .setCustomerNotificationRequired(item.getCustomerNotificationRequired())
                .setProposedByName(item.getProposedByName()).setProposedTime(item.getProposedTime())
                .setReviewedByName(item.getReviewedByName()).setReviewedTime(item.getReviewedTime())
                .setExecutedByName(item.getExecutedByName()).setExecutedTime(item.getExecutedTime())
                .setVerifiedByName(item.getVerifiedByName()).setVerifiedTime(item.getVerifiedTime())
                .setVerificationConclusion(item.getVerificationConclusion()).setClosedTime(item.getClosedTime())
                .setLastError(item.getLastError());
    }
}
