package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesMaterialConsumptionDTO;
import com.kitex.mom.mes.entity.MesReportMaterialConsumeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.entity.MesWarehouseInoutEntity;
import com.kitex.mom.mes.enums.InOutEnum;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.service.MesReportMaterialConsumeService;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseInoutService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按工单读取有效报工对应的真实物料消耗。
 *
 * <p>人工投料只使用mes_report_material_consume分配账，倒冲只使用报工关联的
 * back_flush_out库存流水；单纯投料、领料或当前used_num不能替代本工具的实际消耗口径。</p>
 */
@Service
@RequiredArgsConstructor
public class MesMaterialConsumptionQueryService {

    public static final String TOOL_CODE = "MES_MATERIAL_CONSUMPTION_QUERY@1";
    private static final int MAX_REPORTS = 500;
    private static final int MAX_CONSUMPTION_ROWS = 5000;
    private static final String UNKNOWN_UNIT = "UNKNOWN";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskReportService reportService;
    private final MesReportMaterialConsumeService consumeService;
    private final MesWarehouseFeedService feedService;
    private final MesWarehouseInoutService inoutService;

    public MesMaterialConsumptionDTO.Evidence query(MesMaterialConsumptionDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !actualFactory.equals(query.getFactoryCode())) {
            throw new CheckedException("物料实耗查询工厂与当前实际工厂不一致");
        }
        String taskCode = normalizeTaskCode(query.getTaskCode());
        int detailLimit = query.getLimit() == null ? 20 : Math.max(1, Math.min(query.getLimit(), 50));
        query.setTaskCode(taskCode).setLimit(detailLimit);

        LambdaQueryWrapper<MesTaskReportEntity> reportScope = effectiveReports(actualFactory, taskCode);
        long effectiveReportCount = reportService.count(reportScope);
        List<MesTaskReportEntity> reports = reportService.list(
                effectiveReports(actualFactory, taskCode)
                        .orderByAsc(MesTaskReportEntity::getReportTime)
                        .orderByAsc(MesTaskReportEntity::getId)
                        .last("LIMIT " + (MAX_REPORTS + 1)));
        boolean reportTruncated = reports.size() > MAX_REPORTS || effectiveReportCount > MAX_REPORTS;
        if (reports.size() > MAX_REPORTS) {
            reports = reports.subList(0, MAX_REPORTS);
        }
        List<Long> reportIds = reports.stream().map(MesTaskReportEntity::getId).toList();

        List<MesReportMaterialConsumeEntity> manualRows = reportIds.isEmpty() ? List.of()
                : consumeService.list(effectiveManual(actualFactory, reportIds)
                .orderByAsc(MesReportMaterialConsumeEntity::getId)
                .last("LIMIT " + (MAX_CONSUMPTION_ROWS + 1)));
        List<MesWarehouseInoutEntity> backflushRows = reportIds.isEmpty() ? List.of()
                : inoutService.list(backflush(actualFactory, reportIds)
                .orderByAsc(MesWarehouseInoutEntity::getId)
                .last("LIMIT " + (MAX_CONSUMPTION_ROWS + 1)));
        boolean manualTruncated = manualRows.size() > MAX_CONSUMPTION_ROWS;
        boolean backflushTruncated = backflushRows.size() > MAX_CONSUMPTION_ROWS;
        if (manualTruncated) {
            manualRows = manualRows.subList(0, MAX_CONSUMPTION_ROWS);
        }
        if (backflushTruncated) {
            backflushRows = backflushRows.subList(0, MAX_CONSUMPTION_ROWS);
        }

        Set<Long> feedIds = manualRows.stream().map(MesReportMaterialConsumeEntity::getFeedId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MesWarehouseFeedEntity> feeds = feedIds.isEmpty() ? Map.of()
                : feedService.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                        .in(MesWarehouseFeedEntity::getId, feedIds)
                        .eq(MesWarehouseFeedEntity::getFactoryCode, actualFactory)).stream()
                .collect(Collectors.toMap(MesWarehouseFeedEntity::getId, Function.identity(),
                        (left, right) -> left));

        Map<GroupKey, Aggregate> grouped = new LinkedHashMap<>();
        List<String> refs = new ArrayList<>();
        for (MesReportMaterialConsumeEntity row : manualRows) {
            MesWarehouseFeedEntity feed = feeds.get(row.getFeedId());
            String unit = feed == null || !StringUtils.hasText(feed.getMatUnit())
                    ? UNKNOWN_UNIT : feed.getMatUnit();
            GroupKey key = new GroupKey("MANUAL_FEED", row.getRequirementMatCode(), row.getMatCode(),
                    feed == null ? null : feed.getMatName(), row.getMatBatch(), unit,
                    Boolean.TRUE.equals(row.getSubstituteFlag()));
            grouped.computeIfAbsent(key, ignored -> new Aggregate()).add(
                    row.getRequirementNum(), row.getConsumeNum());
            if (refs.size() < 200) {
                refs.add("MES:mes_report_material_consume:" + row.getId());
            }
        }
        for (MesWarehouseInoutEntity row : backflushRows) {
            BigDecimal ratio = row.getSubstituteRatio();
            BigDecimal requirement = ratio != null && ratio.compareTo(BigDecimal.ZERO) > 0
                    ? zero(row.getOperateNum()).divide(ratio, 12, RoundingMode.HALF_UP) : null;
            String unit = StringUtils.hasText(row.getMatUnit()) ? row.getMatUnit() : UNKNOWN_UNIT;
            GroupKey key = new GroupKey("BACKFLUSH", row.getRequirementMatCode(), row.getMatCode(),
                    row.getMatName(), row.getMatBatch(), unit, Boolean.TRUE.equals(row.getSubstituteFlag()));
            grouped.computeIfAbsent(key, ignored -> new Aggregate()).add(requirement, row.getOperateNum());
            if (refs.size() < 200) {
                refs.add("MES:mes_warehouse_inout:" + row.getId());
            }
        }

        List<MesMaterialConsumptionDTO.MaterialConsumption> allGroups = grouped.entrySet().stream()
                .map(entry -> toConsumption(entry.getKey(), entry.getValue())).toList();
        List<MesMaterialConsumptionDTO.MaterialConsumption> details = allGroups.stream()
                .limit(detailLimit).toList();
        boolean detailTruncated = allGroups.size() > details.size();
        boolean referenceTruncated = manualRows.size() + backflushRows.size() > 200;
        boolean truncated = reportTruncated || manualTruncated || backflushTruncated
                || detailTruncated || referenceTruncated;

        long manualCount = reportIds.isEmpty() ? 0L
                : consumeService.count(effectiveManual(actualFactory, reportIds));
        long backflushCount = reportIds.isEmpty() ? 0L
                : inoutService.count(backflush(actualFactory, reportIds));
        long substituteCount = reportIds.isEmpty() ? 0L
                : consumeService.count(effectiveManual(actualFactory, reportIds)
                .eq(MesReportMaterialConsumeEntity::getSubstituteFlag, true))
                + inoutService.count(backflush(actualFactory, reportIds)
                .eq(MesWarehouseInoutEntity::getSubstituteFlag, true));
        MesMaterialConsumptionDTO.Summary summary = new MesMaterialConsumptionDTO.Summary()
                .setEffectiveReportCount(effectiveReportCount)
                .setManualConsumptionRecordCount(manualCount)
                .setBackflushConsumptionRecordCount(backflushCount)
                .setSubstituteConsumptionRecordCount(substituteCount)
                .setDistinctMaterialBatchCount((long) grouped.size());

        List<String> gaps = dataGaps(effectiveReportCount, manualCount, backflushCount,
                grouped, reportTruncated, manualTruncated, backflushTruncated, allGroups.size(), details.size());
        if (referenceTruncated) {
            gaps.add("实耗证据引用超过200条，本次仅返回前200条引用；汇总仍按受控查询范围计算");
        }
        return new MesMaterialConsumptionDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("只汇总指定工单有效报工关联的实际消耗：人工投料取mes_report_material_consume有效分配账，倒冲取报工ID关联的back_flush_out出库流水；已冲销报工及消耗被排除。需求当量和实际消耗按来源、需求物料、实际物料、批次和单位分组，禁止跨单位合计。")
                .setFactoryCode(actualFactory).setTaskCode(taskCode).setObservedAt(LocalDateTime.now())
                .setSummary(summary).setConsumptions(details).setDataGaps(gaps)
                .setEvidenceRefs(refs.stream().distinct().toList()).setTruncated(truncated);
    }

    private LambdaQueryWrapper<MesTaskReportEntity> effectiveReports(String factory, String taskCode) {
        return new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getFactoryCode, factory)
                .eq(MesTaskReportEntity::getTaskCode, taskCode)
                .and(status -> status.isNull(MesTaskReportEntity::getStatus)
                        .or().eq(MesTaskReportEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()));
    }

    private LambdaQueryWrapper<MesReportMaterialConsumeEntity> effectiveManual(
            String factory, List<Long> reportIds) {
        return new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
                .eq(MesReportMaterialConsumeEntity::getFactoryCode, factory)
                .in(MesReportMaterialConsumeEntity::getReportId, reportIds)
                .and(status -> status.isNull(MesReportMaterialConsumeEntity::getStatus)
                        .or().eq(MesReportMaterialConsumeEntity::getStatus, ReportStatusEnum.EFFECTIVE.name()));
    }

    private LambdaQueryWrapper<MesWarehouseInoutEntity> backflush(String factory,
                                                                  List<Long> reportIds) {
        return new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                .eq(MesWarehouseInoutEntity::getFactoryCode, factory)
                .in(MesWarehouseInoutEntity::getBillId, reportIds)
                .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode())
                .eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.OUT.getCode());
    }

    private MesMaterialConsumptionDTO.MaterialConsumption toConsumption(GroupKey key, Aggregate value) {
        return new MesMaterialConsumptionDTO.MaterialConsumption()
                .setSourceType(key.sourceType()).setRequirementMaterialCode(key.requirementMaterialCode())
                .setActualMaterialCode(key.actualMaterialCode()).setActualMaterialName(key.actualMaterialName())
                .setBatchNo(key.batchNo()).setUnit(key.unit())
                .setRequirementQuantity(value.requirementKnown ? value.requirementQuantity : null)
                .setConsumptionQuantity(value.consumptionQuantity)
                .setSubstituteFlag(key.substitute()).setRecordCount(value.recordCount);
    }

    private List<String> dataGaps(long reportCount, long manualCount, long backflushCount,
                                  Map<GroupKey, Aggregate> groups, boolean reportTruncated,
                                  boolean manualTruncated, boolean backflushTruncated,
                                  int groupCount, int returnedGroupCount) {
        List<String> gaps = new ArrayList<>();
        if (reportCount == 0) {
            gaps.add("该工单没有有效报工，不能形成实际物料消耗证据");
        } else if (manualCount + backflushCount == 0) {
            gaps.add("该工单存在有效报工，但没有人工投料消耗分配账或倒冲出库流水；不能把领料或投料量当作实际消耗");
        }
        long unknownUnits = groups.keySet().stream().filter(key -> UNKNOWN_UNIT.equals(key.unit())).count();
        if (unknownUnits > 0) {
            gaps.add(unknownUnits + "组历史实耗缺少冻结单位，只能在UNKNOWN组展示，不能与其他单位合计");
        }
        long missingRequirement = groups.keySet().stream()
                .filter(key -> !StringUtils.hasText(key.requirementMaterialCode())).count();
        if (missingRequirement > 0) {
            gaps.add(missingRequirement + "组历史实耗缺少BOM需求物料编码，无法证明主料/替代料需求归属");
        }
        if (reportTruncated) {
            gaps.add("有效报工超过" + MAX_REPORTS + "笔，本次实耗证据仅覆盖最早的受控上限，禁止视为工单完整总耗");
        }
        if (manualTruncated || backflushTruncated) {
            gaps.add("实耗明细超过单类" + MAX_CONSUMPTION_ROWS + "行受控上限，返回结果不代表完整总耗");
        }
        if (groupCount > returnedGroupCount) {
            gaps.add("实耗共有" + groupCount + "个物料批次单位分组，本次返回" + returnedGroupCount + "组明细");
        }
        return gaps;
    }

    private String normalizeTaskCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new CheckedException("生产工单号不能为空");
        }
        String normalized = code.trim();
        if (normalized.length() > 100) {
            throw new CheckedException("生产工单号不能超过100位");
        }
        return normalized;
    }

    private BigDecimal zero(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO);
    }

    private record GroupKey(String sourceType, String requirementMaterialCode,
                            String actualMaterialCode, String actualMaterialName,
                            String batchNo, String unit, boolean substitute) {
    }

    private static final class Aggregate {
        private BigDecimal requirementQuantity = BigDecimal.ZERO;
        private BigDecimal consumptionQuantity = BigDecimal.ZERO;
        private boolean requirementKnown;
        private long recordCount;

        private void add(BigDecimal requirement, BigDecimal consumption) {
            if (requirement != null) {
                requirementQuantity = requirementQuantity.add(requirement);
                requirementKnown = true;
            }
            consumptionQuantity = consumptionQuantity.add(
                    Objects.requireNonNullElse(consumption, BigDecimal.ZERO));
            recordCount++;
        }
    }
}
