package com.kitex.mom.wms.agent.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.agent.dto.WmsStockDispositionReceiptDTO;
import com.kitex.mom.wms.agent.mapper.WmsAgentInventoryHealthMapper;
import com.kitex.mom.wms.agent.mapper.WmsStockDispositionReceiptMapper;
import com.kitex.mom.wms.agent.model.WmsInventoryHealthRow;
import com.kitex.mom.wms.entity.WmsQualityStatusOperationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 效果复查专用：只回传WMS正式处置账和当前行风险，不接受模型提供的单号作为核验事实。
 */
@Service
@RequiredArgsConstructor
public class WmsStockDispositionReceiptService {

    private static final int MAX_LINEAGE_NODES = 64;
    private static final int MAX_LINEAGE_DEPTH = 8;

    private final WmsAgentFactoryAccessService factoryAccess;
    private final WmsAgentInventoryHealthMapper inventory;
    private final WmsStockDispositionReceiptMapper receipts;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WmsStockDispositionReceiptDTO.Evidence query(WmsStockDispositionReceiptDTO.Query query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())
                || query.getStockId() == null || !query.getStockId().matches("[1-9][0-9]{0,18}")) {
            throw new CheckedException("库存处置回执必须指定当前工厂和有效库存主键");
        }
        long stockId;
        try {
            stockId = Long.parseLong(query.getStockId());
        } catch (NumberFormatException overflow) {
            throw new CheckedException("库存主键超出数据库bigint范围");
        }
        var scope = factoryAccess.requireRead(query.getFactoryCode());
        int nearExpiryDays = query.getNearExpiryDays() == null ? 30 : query.getNearExpiryDays();
        int slowMovingDays = query.getSlowMovingDays() == null ? 90 : query.getSlowMovingDays();
        if (nearExpiryDays < 1 || nearExpiryDays > 3650 || slowMovingDays < 1 || slowMovingDays > 3650) {
            throw new CheckedException("库存回执的效期和库龄阈值超出允许范围");
        }
        LocalDateTime observedAt = LocalDateTime.now();
        if (query.getLineageFrom() != null && query.getLineageFrom().isAfter(observedAt)) {
            throw new CheckedException("库存拆分追溯起点不能晚于当前回采时间");
        }
        WmsStockDispositionReceiptDTO.Evidence evidence = readEvidence(scope, stockId, observedAt,
                nearExpiryDays, slowMovingDays);
        if (query.getLineageFrom() != null) {
            appendSplitLineage(evidence, scope, query.getLineageFrom(), observedAt,
                    nearExpiryDays, slowMovingDays);
        }
        return evidence;
    }

    /**
     * 在同一个可重复读快照中采集活动行、软归档行和正式质量事务。
     */
    private WmsStockDispositionReceiptDTO.Evidence readEvidence(WmsAgentFactoryAccessService.Scope scope,
                                                                long stockId, LocalDateTime observedAt,
                                                                int nearExpiryDays, int slowMovingDays) {
        WmsInventoryHealthRow.ExceptionStock row = inventory.selectStockById(scope.tenantId(),
                scope.factoryCode(), null, stockId, observedAt.toLocalDate(),
                observedAt.toLocalDate().plusDays(nearExpiryDays), observedAt.minusDays(slowMovingDays));
        List<WmsQualityStatusOperationEntity> history = receipts.selectByStockId(scope.tenantId(),
                scope.factoryCode(), Long.toString(stockId));
        WmsStockDispositionReceiptDTO.ArchivedStock archived = row == null
                ? receipts.selectArchivedStock(scope.tenantId(), scope.factoryCode(), stockId) : null;
        if (row == null && archived == null && history.isEmpty()) {
            throw new CheckedException("当前工厂既无活动库存行也无匹配的正式质量处置回执");
        }
        boolean truncated = history.size() > 100;
        List<String> exceptions = row == null || StrUtil.isBlank(row.getExceptionCodes()) ? List.of()
                : Arrays.stream(row.getExceptionCodes().split(",")).map(String::trim)
                .filter(StrUtil::isNotBlank).distinct().toList();
        WmsStockDispositionReceiptDTO.Evidence evidence = new WmsStockDispositionReceiptDTO.Evidence()
                .setFactoryCode(scope.factoryCode()).setStockId(Long.toString(stockId))
                .setObservedAt(observedAt).setStockPresent(row != null)
                .setArchivedOriginalStock(archived)
                .setCurrentExceptionTypes(exceptions).setReceiptHistoryTruncated(truncated)
                .setSplitLineageTruncated(false);
        if (row != null) {
            evidence.setCurrentStockUpdatedAt(row.getStockUpdatedAt())
                    .setCurrentPhysicalNum(row.getPhysicalNum())
                    .setCurrentProductionReservedNum(row.getProductionReservedNum())
                    .setCurrentSalesReservedNum(row.getSalesReservedNum())
                    .setCurrentAvailableNum(row.getAvailableNum())
                    .setCurrentMaterialCode(row.getMatCode())
                    .setCurrentMaterialBatch(row.getMatBatch())
                    .setCurrentMaterialBarcode(row.getMatBarcode())
                    .setCurrentSupplierCode(row.getSupplierCode())
                    .setCurrentWarehouseCode(row.getWarehouseCode())
                    .setCurrentSiteCode(row.getSiteCode())
                    .setCurrentContainerCode(row.getContainerCode())
                    .setCurrentUnitCode(row.getMatUnit())
                    .setCurrentQualityStatus(row.getQualityStatus())
                    .setCurrentQualityHoldNo(row.getQualityHoldNo())
                    .setCurrentQualitySourceType(row.getQualitySourceType())
                    .setCurrentQualitySourceId(row.getQualitySourceId())
                    .setCurrentLockStatus(row.getLockStatus());
        }
        evidence.getEvidenceRefs().add("WMS:wms_live_stock:" + stockId);
        if (archived != null) evidence.getEvidenceRefs().add("WMS:wms_live_stock_archive:" + stockId);
        for (WmsQualityStatusOperationEntity operation : history.stream().limit(100).toList()) {
            evidence.getQualityOperations().add(new WmsStockDispositionReceiptDTO.QualityOperation()
                    .setOperationId(String.valueOf(operation.getId()))
                    .setRequestId(operation.getRequestId()).setHoldNo(operation.getHoldNo())
                    .setSourceType(operation.getSourceType()).setSourceId(operation.getSourceId())
                    .setAction(operation.getAction()).setStatus(operation.getStatus())
                    .setRequestedQuantity(operation.getRequestedQuantity())
                    .setAffectedQuantity(operation.getAffectedQuantity())
                    .setAffectedStockIds(operation.getAffectedStockIds())
                    .setSplitRecorded(operation.getSplitRecorded())
                    .setSplitRemainderStockIds(operation.getSplitRemainderStockIds())
                    .setSplitLineageJson(operation.getSplitLineageJson())
                    .setSplitRecordedAt(operation.getSplitRecordedAt())
                    .setExecutionRef(operation.getExecutionRef()).setExecutedBy(operation.getExecutedBy())
                    .setExecutedTime(operation.getExecutedTime())
                    .setCreatedTime(operation.getCreateTime()).setUpdatedTime(operation.getUpdateTime()));
            evidence.getEvidenceRefs().add("WMS:wms_quality_status_operation:" + operation.getId());
        }
        return evidence;
    }

    /**
     * 只沿WMS事务持久记录的余量ID展开；循环、重复、缺行和上限均作为不完整证据返回。
     */
    private void appendSplitLineage(WmsStockDispositionReceiptDTO.Evidence root,
                                    WmsAgentFactoryAccessService.Scope scope, LocalDateTime lineageFrom,
                                    LocalDateTime observedAt, int nearExpiryDays, int slowMovingDays) {
        Deque<LineageNode> pending = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        seen.add(Long.parseLong(root.getStockId()));
        pending.add(new LineageNode(root, 0));
        while (!pending.isEmpty()) {
            LineageNode parent = pending.removeFirst();
            if (Boolean.TRUE.equals(parent.evidence().getReceiptHistoryTruncated())) {
                root.setSplitLineageTruncated(true)
                        .setSplitLineageIssue("库存拆分节点的正式事务历史不完整");
                return;
            }
            for (WmsStockDispositionReceiptDTO.QualityOperation operation : parent.evidence().getQualityOperations()) {
                if (!Boolean.TRUE.equals(operation.getSplitRecorded())
                        || StrUtil.isBlank(operation.getSplitRemainderStockIds())) continue;
                if (operation.getSplitRecordedAt() == null) {
                    root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分事务缺少实际拆分时间");
                    return;
                }
                if (!operation.getSplitRecordedAt().isAfter(lineageFrom)) continue;
                JSONArray links;
                try {
                    if (StrUtil.isBlank(operation.getSplitLineageJson()))
                        throw new IllegalArgumentException("拆分亲缘映射为空");
                    if (operation.getSplitLineageJson().length() > 65536)
                        throw new IllegalArgumentException("拆分亲缘映射超过安全长度");
                    links = JSONUtil.parseArray(operation.getSplitLineageJson());
                } catch (RuntimeException invalid) {
                    root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分事务缺少有效的原行与余量映射");
                    return;
                }
                Set<String> recordedRemainders = new HashSet<>();
                for (int i = 0; i < links.size(); i++) {
                    var link = links.getJSONObject(i);
                    String parentId = link == null ? null : link.getStr("parentStockId");
                    String remainderId = link == null ? null : link.getStr("remainderStockId");
                    String quantity = link == null ? null : link.getStr("remainderQuantity");
                    if (parentId == null || !parentId.matches("[1-9][0-9]{0,18}")
                            || remainderId == null || !remainderId.matches("[1-9][0-9]{0,18}")
                            || quantity == null || !quantity.matches("[0-9]+(\\.[0-9]+)?")
                            || new BigDecimal(quantity).signum() <= 0
                            || StrUtil.isBlank(operation.getAffectedStockIds())
                            || Arrays.stream(operation.getAffectedStockIds().split(","))
                            .map(String::trim).noneMatch(parentId::equals)
                            || !recordedRemainders.add(remainderId)) {
                        root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分映射的库存主键或数量不合法");
                        return;
                    }
                }
                Set<String> listedRemainders = new HashSet<>();
                String[] listedIds = operation.getSplitRemainderStockIds().split(",", -1);
                for (String listed : listedIds)
                    listedRemainders.add(listed.trim());
                if (links.isEmpty() || links.size() > MAX_LINEAGE_NODES
                        || listedRemainders.size() != listedIds.length
                        || !Objects.equals(recordedRemainders, listedRemainders)) {
                    root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分映射与余量库存行清单不一致");
                    return;
                }
                for (int i = 0; i < links.size(); i++) {
                    var link = links.getJSONObject(i);
                    if (!Objects.equals(parent.evidence().getStockId(), link.getStr("parentStockId"))) continue;
                    if (parent.depth() >= MAX_LINEAGE_DEPTH) {
                        root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分层级超过安全取证上限");
                        return;
                    }
                    String childId = link.getStr("remainderStockId");
                    if (!childId.matches("[1-9][0-9]{0,18}")) {
                        root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分事务记录了无效余量主键");
                        return;
                    }
                    long parsedId;
                    try {
                        parsedId = Long.parseLong(childId);
                    } catch (NumberFormatException overflow) {
                        root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分余量主键超出数据库范围");
                        return;
                    }
                    if (!seen.add(parsedId) || seen.size() > MAX_LINEAGE_NODES) {
                        root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分链出现重复库存行或超出取证上限");
                        return;
                    }
                    WmsStockDispositionReceiptDTO.Evidence child;
                    try {
                        child = readEvidence(scope, parsedId, observedAt, nearExpiryDays, slowMovingDays);
                    } catch (CheckedException missing) {
                        root.setSplitLineageTruncated(true).setSplitLineageIssue("拆分余量库存行或正式事务缺失：" + childId);
                        return;
                    }
                    root.getSplitDescendants().add(child);
                    pending.addLast(new LineageNode(child, parent.depth() + 1));
                }
            }
        }
    }

    private record LineageNode(WmsStockDispositionReceiptDTO.Evidence evidence, int depth) {
    }
}
