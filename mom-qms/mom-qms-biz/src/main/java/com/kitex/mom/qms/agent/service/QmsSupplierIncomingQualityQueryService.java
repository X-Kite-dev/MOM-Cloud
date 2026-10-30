package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.agent.dto.QmsSupplierIncomingQualityDTO;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import com.kitex.mom.qms.service.InspectionPurchaseTaskService;
import com.kitex.mom.qms.service.QmsBadInfoService;
import com.kitex.mom.qms.service.QmsCapaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 查询供应商—物料在明确时间窗内的来料质量、NCR及CAPA事实。
 *
 * <p>统计值由数据库事实确定性计算；缺少单位、超出行数限制或未完成任务时均显式披露，
 * 不把有限样本解释为供应商全生命周期质量水平。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsSupplierIncomingQualityQueryService {

    public static final String TOOL_CODE = "QMS_SUPPLIER_INCOMING_QUALITY_QUERY@1";
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> ACCEPTED_RESULTS = Set.of("QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE");

    private final QmsAgentFactoryAccessService factoryAccess;
    private final InspectionPurchaseTaskService purchaseTaskService;
    private final QmsBadInfoService badInfoService;
    private final QmsCapaService capaService;
    private final ObjectMapper objectMapper;

    public QmsSupplierIncomingQualityDTO.Evidence query(QmsSupplierIncomingQualityDTO.Query query) {
        validate(query);
        String factory = factoryAccess.requireRead(query.getFactoryCode());
        int limit = query.getLimit() == null ? DEFAULT_LIMIT : query.getLimit();
        LambdaQueryWrapper<QmsInspectionPurchaseTaskEntity> taskScope = taskScope(query, factory);
        long totalTaskCount = purchaseTaskService.count(taskScope);
        List<QmsInspectionPurchaseTaskEntity> tasks = purchaseTaskService.list(taskScope(query, factory)
                .orderByDesc(QmsInspectionPurchaseTaskEntity::getCreateTime)
                .orderByDesc(QmsInspectionPurchaseTaskEntity::getId).last("LIMIT " + limit));
        List<Long> taskIds = tasks.stream().map(QmsInspectionPurchaseTaskEntity::getId).toList();
        List<QmsBadInfoEntity> ncrs = taskIds.isEmpty() ? List.of() : badInfoService.list(
                new LambdaQueryWrapper<QmsBadInfoEntity>()
                        .eq(QmsBadInfoEntity::getFactoryCode, factory)
                        .eq(QmsBadInfoEntity::getResourceType, "PURCHASE")
                        .in(QmsBadInfoEntity::getResourceTaskId, taskIds)
                        .eq(QmsBadInfoEntity::getSupplierCode, query.getSupplierCode())
                        .eq(QmsBadInfoEntity::getMatCode, query.getMaterialCode())
                        .orderByDesc(QmsBadInfoEntity::getCreateTime)
                        .orderByDesc(QmsBadInfoEntity::getId));
        List<Long> capaIds = ncrs.stream().map(QmsBadInfoEntity::getCapaId)
                .filter(Objects::nonNull).distinct().toList();
        List<QmsCapaEntity> capas = capaIds.isEmpty() ? List.of() : capaService.list(
                new LambdaQueryWrapper<QmsCapaEntity>()
                        .eq(QmsCapaEntity::getFactoryCode, factory)
                        .in(QmsCapaEntity::getId, capaIds)
                        .orderByDesc(QmsCapaEntity::getCreateTime)
                        .orderByDesc(QmsCapaEntity::getId));

        long finished = tasks.stream().filter(item -> "FINISHED".equals(item.getStatus())).count();
        long accepted = tasks.stream().filter(this::accepted).count();
        long unqualified = tasks.stream().filter(item -> "FINISHED".equals(item.getStatus())
                && !ACCEPTED_RESULTS.contains(item.getResult())).count();
        BigDecimal submitted = tasks.stream().map(item -> decimal(item.getNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal usable = tasks.stream().map(item -> decimal(item.getUsableNum()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rejected = tasks.stream().filter(item -> "FINISHED".equals(item.getStatus()))
                .map(item -> decimal(item.getNum()).subtract(decimal(item.getUsableNum())).max(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean truncated = totalTaskCount > tasks.size();
        List<String> gaps = gaps(query, totalTaskCount, tasks, ncrs, capas, truncated);
        List<String> refs = refs(tasks, ncrs, capas);
        QmsSupplierIncomingQualityDTO.Evidence evidence = new QmsSupplierIncomingQualityDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("仅统计指定工厂、供应商编码、物料编码和时间窗内的QMS来料任务。任务通过率=已完成且结果为QUALIFIED/EXEMPTION/RELEASE/RECEIVE的任务数÷已完成任务数；数量均保持PMS冻结的单一物料单位，不输出未经完整分母支持的PPM。")
                .setFactoryCode(factory).setSupplierCode(query.getSupplierCode())
                .setMaterialCode(query.getMaterialCode()).setMaterialUnit(query.getMaterialUnit())
                .setStartTime(query.getStartTime()).setEndTime(query.getEndTime())
                .setObservedAt(LocalDateTime.now()).setTotalTaskCount(totalTaskCount)
                .setFinishedTaskCount(finished).setAcceptedTaskCount(accepted)
                .setUnqualifiedTaskCount(unqualified).setSubmittedQuantity(submitted)
                .setUsableQuantity(usable).setRejectedQuantity(rejected)
                .setFinishedTaskPassRate(rate(accepted, finished)).setTruncated(truncated)
                .setTasks(tasks.stream().map(this::task).toList())
                .setNonconformances(ncrs.stream().map(this::ncr).toList())
                .setCapas(capas.stream().map(this::capa).toList())
                .setRecurringDefects(patterns(ncrs)).setDataGaps(gaps)
                .setLimitations(List.of("任务通过率是所选时间窗内的任务口径，不等于抽样缺陷PPM或供应商终身评级",
                        "NCR/CAPA状态只用于改善线索，正式判定、审核、措施和关闭仍由QMS原流程完成",
                        "模型可归纳重复缺陷和拟定改善建议，但不能修改供应商资格、免检、评级或质量结论"))
                .setEvidenceRefs(refs);
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private void validate(QmsSupplierIncomingQualityDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getSupplierCode())
                || !StringUtils.hasText(query.getMaterialCode()) || !StringUtils.hasText(query.getMaterialUnit())) {
            throw new CheckedException("供应商来料质量查询缺少供应商、物料或单位");
        }
        if (query.getStartTime() == null || query.getEndTime() == null
                || !query.getStartTime().isBefore(query.getEndTime())) {
            throw new CheckedException("供应商来料质量查询必须提供有序时间窗");
        }
        long days = Duration.between(query.getStartTime(), query.getEndTime()).toDays();
        if (days < 1 || days > 366 || query.getEndTime().isAfter(LocalDateTime.now().plusMinutes(5))) {
            throw new CheckedException("供应商来料质量时间窗须为1至366天且不能超出当前时刻");
        }
        if (query.getLimit() != null && (query.getLimit() < 1 || query.getLimit() > MAX_LIMIT)) {
            throw new CheckedException("供应商来料质量明细上限须为1至500");
        }
    }

    private LambdaQueryWrapper<QmsInspectionPurchaseTaskEntity> taskScope(
            QmsSupplierIncomingQualityDTO.Query query, String factory) {
        return new LambdaQueryWrapper<QmsInspectionPurchaseTaskEntity>()
                .eq(QmsInspectionPurchaseTaskEntity::getFactoryCode, factory)
                .eq(QmsInspectionPurchaseTaskEntity::getSupplierCode, query.getSupplierCode())
                .eq(QmsInspectionPurchaseTaskEntity::getMatCode, query.getMaterialCode())
                .ge(QmsInspectionPurchaseTaskEntity::getCreateTime, query.getStartTime())
                .lt(QmsInspectionPurchaseTaskEntity::getCreateTime, query.getEndTime());
    }

    private List<String> gaps(QmsSupplierIncomingQualityDTO.Query query, long total,
                              List<QmsInspectionPurchaseTaskEntity> tasks, List<QmsBadInfoEntity> ncrs,
                              List<QmsCapaEntity> capas, boolean truncated) {
        List<String> gaps = new ArrayList<>();
        if (total == 0) gaps.add("所选时间窗没有该供应商—物料的来料检验任务");
        if (truncated) gaps.add("来料任务超过明细上限，当前证据被截断，不能据此形成完整趋势结论");
        tasks.stream().filter(item -> !"FINISHED".equals(item.getStatus()))
                .forEach(item -> gaps.add("来料任务" + item.getId() + "尚未完成，不能计入完成任务通过率"));
        tasks.stream().filter(item -> item.getNum() == null || item.getNum() < 0
                        || item.getUsableNum() == null || item.getUsableNum() < 0
                        || item.getUsableNum() > item.getNum())
                .forEach(item -> gaps.add("来料任务" + item.getId() + "提交量或可用量越界"));
        Set<Long> returnedCapas = new LinkedHashSet<>();
        capas.forEach(item -> returnedCapas.add(item.getId()));
        ncrs.stream().filter(item -> item.getCapaId() != null && !returnedCapas.contains(item.getCapaId()))
                .forEach(item -> gaps.add("NCR" + item.getId() + "关联的CAPA不在当前工厂或已不可读取"));
        if (!StringUtils.hasText(query.getMaterialUnit())) gaps.add("物料单位缺失，禁止数量汇总");
        return gaps;
    }

    private QmsSupplierIncomingQualityDTO.InspectionTask task(QmsInspectionPurchaseTaskEntity item) {
        return new QmsSupplierIncomingQualityDTO.InspectionTask()
                .setTaskId(text(item.getId())).setTaskCode(item.getTaskCode())
                .setPurchaseOrderDetailId(text(item.getOrderDetailId())).setBatchNo(item.getBatchNo())
                .setStatus(item.getStatus()).setResult(item.getResult())
                .setSubmittedQuantity(decimal(item.getNum())).setUsableQuantity(decimal(item.getUsableNum()))
                .setInspectionLevel(item.getInspectionLevel())
                .setMaterialAuthorizationNo(item.getMaterialAuthorizationNo())
                .setLatestInspectionTime(item.getLatestInspectionTime() == null ? null
                        : LocalDateTime.ofInstant(item.getLatestInspectionTime().toInstant(), ZoneId.systemDefault()));
    }

    private QmsSupplierIncomingQualityDTO.Nonconformance ncr(QmsBadInfoEntity item) {
        return new QmsSupplierIncomingQualityDTO.Nonconformance()
                .setNcrId(text(item.getId())).setNcrNo(item.getNcrNo())
                .setTaskId(text(item.getResourceTaskId())).setBatchNo(item.getBatchNo())
                .setBadType(item.getBadType()).setStatus(item.getStatus()).setQuantity(item.getNum())
                .setCapaId(text(item.getCapaId())).setCapaStatus(item.getCapaStatus());
    }

    private QmsSupplierIncomingQualityDTO.Capa capa(QmsCapaEntity item) {
        return new QmsSupplierIncomingQualityDTO.Capa()
                .setCapaId(text(item.getId())).setCapaNo(item.getCapaNo())
                .setSourceNcrId(text(item.getSourceId())).setTitle(item.getTitle()).setStatus(item.getStatus())
                .setRecurrenceCount(item.getRecurrenceCount()).setRiskPriorityNo(item.getRiskPriorityNo())
                .setDueTime(item.getDueTime()).setEffectivenessResult(item.getEffectivenessResult())
                .setClosedTime(item.getClosedTime());
    }

    private List<QmsSupplierIncomingQualityDTO.DefectPattern> patterns(List<QmsBadInfoEntity> ncrs) {
        Map<String, List<QmsBadInfoEntity>> groups = new HashMap<>();
        ncrs.forEach(item -> groups.computeIfAbsent(
                StringUtils.hasText(item.getBadType()) ? item.getBadType() : "UNCLASSIFIED",
                ignored -> new ArrayList<>()).add(item));
        return groups.entrySet().stream().map(entry -> {
                    List<QmsBadInfoEntity> rows = entry.getValue();
                    long batches = rows.stream().map(QmsBadInfoEntity::getBatchNo)
                            .filter(StringUtils::hasText).distinct().count();
                    return new QmsSupplierIncomingQualityDTO.DefectPattern()
                            .setDefectType(entry.getKey()).setOccurrenceCount((long) rows.size())
                            .setAffectedBatchCount(batches)
                            .setNcrIds(rows.stream().map(item -> text(item.getId())).toList());
                }).sorted(Comparator.comparing(QmsSupplierIncomingQualityDTO.DefectPattern::getOccurrenceCount)
                        .reversed().thenComparing(QmsSupplierIncomingQualityDTO.DefectPattern::getDefectType))
                .toList();
    }

    private List<String> refs(List<QmsInspectionPurchaseTaskEntity> tasks, List<QmsBadInfoEntity> ncrs,
                              List<QmsCapaEntity> capas) {
        List<String> refs = new ArrayList<>();
        tasks.forEach(item -> refs.add("QMS:qms_inspection_purchase_task:" + item.getId()));
        ncrs.forEach(item -> refs.add("QMS:qms_bad_info:" + item.getId()));
        capas.forEach(item -> refs.add("QMS:qms_capa:" + item.getId()));
        return List.copyOf(refs);
    }

    private String fingerprint(QmsSupplierIncomingQualityDTO.Evidence evidence) {
        try {
            String facts = objectMapper.writeValueAsString(List.of(evidence.getFactoryCode(),
                    evidence.getSupplierCode(), evidence.getMaterialCode(), evidence.getMaterialUnit(),
                    evidence.getStartTime(), evidence.getEndTime(), evidence.getTotalTaskCount(),
                    evidence.getTasks(), evidence.getNonconformances(), evidence.getCapas(),
                    evidence.getRecurringDefects(), evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("供应商来料质量事实无法生成完整指纹");
        }
    }

    private boolean accepted(QmsInspectionPurchaseTaskEntity item) {
        return "FINISHED".equals(item.getStatus()) && ACCEPTED_RESULTS.contains(item.getResult());
    }

    private BigDecimal rate(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Integer value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private String text(Long value) {
        return value == null ? null : String.valueOf(value);
    }
}
