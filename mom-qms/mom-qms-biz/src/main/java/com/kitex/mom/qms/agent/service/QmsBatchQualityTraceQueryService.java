package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.agent.dto.QmsBatchQualityTraceDTO;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.InspectionProductTaskService;
import com.kitex.mom.qms.service.InspectionPurchaseTaskService;
import com.kitex.mom.qms.service.QmsBadInfoDetailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 指定物料批次的三类检验任务和NCR处置状态快照。
 */
@Service
@RequiredArgsConstructor
public class QmsBatchQualityTraceQueryService {

    public static final String TOOL_CODE = "QMS_BATCH_QUALITY_TRACE_QUERY@1";

    private final QmsAgentFactoryAccessService factoryAccess;
    private final InspectionProcedureTaskService procedureService;
    private final InspectionProductTaskService productService;
    private final InspectionPurchaseTaskService purchaseService;
    private final QmsBadInfoDetailService badDetailService;

    public QmsBatchQualityTraceDTO.Evidence query(QmsBatchQualityTraceDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getMaterialCode())
                || !StringUtils.hasText(query.getBatchNo())) {
            throw new CheckedException("批次质量追溯必须提供完整物料编码和批次号");
        }
        String factory = factoryAccess.requireRead(query.getFactoryCode());
        String material = query.getMaterialCode().trim();
        String batch = query.getBatchNo().trim();
        int limit = query.getDetailLimit() == null ? 50 : Math.max(1, Math.min(query.getDetailLimit(), 100));
        long procedureCount = procedureService.count(procedure(factory, material, batch));
        long productCount = productService.count(product(factory, material, batch));
        long purchaseCount = purchaseService.count(purchase(factory, material, batch));
        long unqualified = procedureService.count(procedure(factory, material, batch).eq(QmsInspectionProcedureTaskEntity::getResult, "UN_QUALIFIED"))
                + productService.count(product(factory, material, batch).eq(QmsInspectionProductTaskEntity::getResult, "UN_QUALIFIED"))
                + purchaseService.count(purchase(factory, material, batch).eq(QmsInspectionPurchaseTaskEntity::getResult, "UN_QUALIFIED"));
        long ncrCount = badDetailService.count(ncr(factory, material, batch));
        long openNcr = badDetailService.count(ncr(factory, material, batch).notIn(QmsBadInfoDetailEntity::getStatus, "CLOSED", "LEGACY_CLOSED"));
        List<QmsBatchQualityTraceDTO.QualityRecord> collectedRecords = new ArrayList<>();
        procedureService.list(procedure(factory, material, batch).orderByDesc(QmsInspectionProcedureTaskEntity::getCreateTime).last("LIMIT " + limit))
                .forEach(item -> collectedRecords.add(fromProcedure(item)));
        productService.list(product(factory, material, batch).orderByDesc(QmsInspectionProductTaskEntity::getCreateTime).last("LIMIT " + limit))
                .forEach(item -> collectedRecords.add(fromProduct(item)));
        purchaseService.list(purchase(factory, material, batch).orderByDesc(QmsInspectionPurchaseTaskEntity::getCreateTime).last("LIMIT " + limit))
                .forEach(item -> collectedRecords.add(fromPurchase(item)));
        badDetailService.list(ncr(factory, material, batch).orderByDesc(QmsBadInfoDetailEntity::getCreateTime).last("LIMIT " + limit))
                .forEach(item -> collectedRecords.add(fromNcr(item)));
        List<QmsBatchQualityTraceDTO.QualityRecord> records = collectedRecords.stream()
                .sorted(Comparator.comparing(QmsBatchQualityTraceDTO.QualityRecord::getOccurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList();
        long total = procedureCount + productCount + purchaseCount + ncrCount;
        List<String> gaps = new ArrayList<>();
        if (total > records.size())
            gaps.add("检验和NCR记录共" + total + "条，本次返回" + records.size() + "条；计数是全量，记录明细按时间截取");
        List<String> limitations = new ArrayList<>();
        if (total == 0) limitations.add("当前QMS没有该物料批次的检验或NCR记录；这是已知零记录，不等同于已检验合格");
        List<String> refs = records.stream().map(item -> "QMS:" + table(item.getRecordType()) + ":" + item.getRecordId()).toList();
        return new QmsBatchQualityTraceDTO.Evidence().setToolCode(TOOL_CODE)
                .setMetricDefinition("按当前工厂、物料编码和批次号精确匹配来料/过程/成品检验任务及NCR明细；状态和结果取观察时点当前快照，不把无记录解释为合格。")
                .setFactoryCode(factory).setMaterialCode(material).setBatchNo(batch).setObservedAt(LocalDateTime.now())
                .setInspectionTaskCount(procedureCount + productCount + purchaseCount)
                .setUnqualifiedInspectionCount(unqualified).setNcrDetailCount(ncrCount).setOpenNcrCount(openNcr)
                .setReturnedRecordCount(records.size()).setRecords(records).setDataGaps(gaps).setLimitations(limitations)
                .setEvidenceRefs(refs).setTruncated(total > records.size());
    }

    private LambdaQueryWrapper<QmsInspectionProcedureTaskEntity> procedure(String factory, String mat, String batch) {
        return new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>().eq(QmsInspectionProcedureTaskEntity::getFactoryCode, factory)
                .eq(QmsInspectionProcedureTaskEntity::getMatCode, mat).eq(QmsInspectionProcedureTaskEntity::getBatchNo, batch);
    }

    private LambdaQueryWrapper<QmsInspectionProductTaskEntity> product(String factory, String mat, String batch) {
        return new LambdaQueryWrapper<QmsInspectionProductTaskEntity>().eq(QmsInspectionProductTaskEntity::getFactoryCode, factory)
                .eq(QmsInspectionProductTaskEntity::getMatCode, mat).eq(QmsInspectionProductTaskEntity::getBatchNo, batch);
    }

    private LambdaQueryWrapper<QmsInspectionPurchaseTaskEntity> purchase(String factory, String mat, String batch) {
        return new LambdaQueryWrapper<QmsInspectionPurchaseTaskEntity>().eq(QmsInspectionPurchaseTaskEntity::getFactoryCode, factory)
                .eq(QmsInspectionPurchaseTaskEntity::getMatCode, mat).eq(QmsInspectionPurchaseTaskEntity::getBatchNo, batch);
    }

    private LambdaQueryWrapper<QmsBadInfoDetailEntity> ncr(String factory, String mat, String batch) {
        return new LambdaQueryWrapper<QmsBadInfoDetailEntity>().eq(QmsBadInfoDetailEntity::getFactoryCode, factory)
                .eq(QmsBadInfoDetailEntity::getMatCode, mat).eq(QmsBadInfoDetailEntity::getBatchNo, batch);
    }

    private QmsBatchQualityTraceDTO.QualityRecord fromProcedure(QmsInspectionProcedureTaskEntity item) {
        return base("PROCEDURE_INSPECTION", item.getId(), item.getSourceTaskCode(), item.getOrderNo(), item.getMatName(),
                null, item.getProcessCode(), item.getStatus(), item.getResult(), decimal(item.getNum()), null,
                item.getHandleResult(), item.getRemark(), toTime(item.getLatestInspectionTime(), item.getCreateTime()));
    }

    private QmsBatchQualityTraceDTO.QualityRecord fromProduct(QmsInspectionProductTaskEntity item) {
        return base("PRODUCT_INSPECTION", item.getId(), item.getSourceTaskCode(), item.getOrderNo(), item.getMatName(),
                null, item.getProcessCode(), item.getStatus(), item.getResult(), decimal(item.getNum()), null,
                item.getHandleResult(), item.getRemark(), toTime(item.getLatestInspectionTime(), item.getCreateTime()));
    }

    private QmsBatchQualityTraceDTO.QualityRecord fromPurchase(QmsInspectionPurchaseTaskEntity item) {
        return base("PURCHASE_INSPECTION", item.getId(), item.getTaskCode(), null, item.getMatName(),
                item.getSupplierCode(), null, item.getStatus(), item.getResult(), decimal(item.getNum()), null,
                item.getHandleResult(), item.getRemark(), toTime(item.getLatestInspectionTime(), item.getCreateTime()));
    }

    private QmsBatchQualityTraceDTO.QualityRecord fromNcr(QmsBadInfoDetailEntity item) {
        return base("NCR", item.getId(), item.getDetailNo(), null, item.getMatName(), item.getSupplierCode(), null,
                item.getStatus(), item.getBadType(), item.getJudgeNum(), item.getQuantityUnit(), item.getHandleResult(),
                item.getBadReason(), item.getCreateTime());
    }

    private QmsBatchQualityTraceDTO.QualityRecord base(String type, Long id, String no, String orderNo,
                                                       String matName, String supplier, String process, String status, String result, BigDecimal quantity,
                                                       String unit, String disposition, String reason, LocalDateTime occurredAt) {
        return new QmsBatchQualityTraceDTO.QualityRecord().setRecordType(type).setRecordId(id).setRecordNo(no)
                .setSourceTaskCode(no).setOrderNo(orderNo).setMaterialName(matName).setSupplierCode(supplier)
                .setProcessCode(process).setStatus(status).setResult(result).setQuantity(quantity)
                .setQuantityUnit(unit).setDisposition(disposition).setReason(reason).setOccurredAt(occurredAt);
    }

    private BigDecimal decimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private LocalDateTime toTime(Date date, LocalDateTime fallback) {
        return date == null ? fallback : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private String table(String type) {
        return switch (type) {
            case "PROCEDURE_INSPECTION" -> "qms_inspection_procedure_task";
            case "PRODUCT_INSPECTION" -> "qms_inspection_product_task";
            case "PURCHASE_INSPECTION" -> "qms_inspection_purchase_task";
            case "NCR" -> "qms_bad_info_detail";
            default -> "quality_record";
        };
    }
}
