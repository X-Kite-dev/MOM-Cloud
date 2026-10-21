package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesBatchTraceDTO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.service.impl.MesTraceEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 原料批次到实际报工产出和包装标识的受控正向追溯。
 *
 * <p>查询复用MES实际消耗证据账，不根据当前BOM反推历史使用关系。</p>
 */
@Service
@RequiredArgsConstructor
public class MesBatchTraceQueryService {

    public static final String TOOL_CODE = "MES_BATCH_TRACE_QUERY@1";
    private static final int PAGE_SIZE = 20;

    private final MesTraceEvidenceService traceEvidenceService;
    private final MesActualFactoryAccessService factoryAccess;

    public MesBatchTraceDTO.Evidence query(MesBatchTraceDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getMaterialCode())
                || !StringUtils.hasText(query.getBatchNo())) {
            throw new CheckedException("批次追溯必须提供完整物料编码和批次号");
        }
        String actualFactory = factoryAccess.require("read");
        if (!Objects.equals(actualFactory, query.getFactoryCode())) {
            throw new CheckedException("批次追溯工厂与当前实际工厂不一致");
        }
        int limit = query.getDetailLimit() == null ? 20 : Math.max(1, Math.min(query.getDetailLimit(), 50));
        Page<MesTaskReportEntity> first = traceEvidenceService.search("MATERIAL",
                query.getMaterialCode().trim(), query.getBatchNo().trim(), 1);
        long total = first.getTotal();
        List<MesTaskReportEntity> reports = new ArrayList<>(first.getRecords());
        long pages = Math.min((limit + PAGE_SIZE - 1L) / PAGE_SIZE, first.getPages());
        for (long page = 2; page <= pages && reports.size() < limit; page++) {
            reports.addAll(traceEvidenceService.search("MATERIAL", query.getMaterialCode().trim(),
                    query.getBatchNo().trim(), page).getRecords());
        }
        reports = reports.stream().limit(limit).toList();
        List<MesBatchTraceDTO.AffectedReport> details = new ArrayList<>();
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        List<String> globalGaps = new ArrayList<>();
        for (MesTaskReportEntity report : reports) {
            MesBatchTraceDTO.AffectedReport item = map(report);
            Map<String, Object> packageResult = traceEvidenceService.packages(report.getId(), 1);
            Object rows = packageResult.get("records");
            if (rows instanceof List<?> list) {
                LinkedHashSet<String> codes = new LinkedHashSet<>();
                for (Object value : list) {
                    if (value instanceof Map<?, ?> row && row.get("content") instanceof MesWrapRecordEntity wrap
                            && StringUtils.hasText(wrap.getWrapCode())) {
                        codes.add(wrap.getWrapCode());
                        refs.add("MES:mes_wrap_record:" + wrap.getId());
                    }
                }
                item.setPackageCodes(codes.stream().toList());
            }
            long packageTotal = number(packageResult.get("total"));
            if (packageTotal > PAGE_SIZE) item.getGaps().add("该报工包装记录超过20条，本次仅返回首20条包装证据");
            refs.add("MES:mes_task_report:" + report.getId());
            details.add(item);
        }
        if (total > details.size()) {
            globalGaps.add("实际消耗共命中" + total + "条报工，本次返回" + details.size()
                    + "条；影响总数准确，报工状态、数量和包装明细只描述返回范围，不得外推全量");
        }
        if (details.stream().anyMatch(item -> !item.getGaps().isEmpty())) {
            globalGaps.add("部分历史报工缺少请求号、冻结单位、明确状态或完整包装证据，不能把缺失事实解释为不存在");
        }
        long effective = details.stream().filter(item -> "EFFECTIVE".equals(item.getStatus())).count();
        long reversed = details.stream().filter(item -> "REVERSED".equals(item.getStatus())).count();
        return new MesBatchTraceDTO.Evidence().setToolCode(TOOL_CODE)
                .setMetricDefinition("按MES实际投料消耗或倒冲库存流水中的物料编码+批次精确关联报工；matchedReportCount是全部命中数，状态数量、产出数量和包装码仅统计returnedReportCount返回明细，不用当前BOM补猜历史关系。")
                .setFactoryCode(actualFactory).setMaterialCode(query.getMaterialCode().trim())
                .setBatchNo(query.getBatchNo().trim()).setObservedAt(LocalDateTime.now())
                .setMatchedReportCount(total).setReturnedReportCount(details.size())
                .setEffectiveReturnedCount(effective).setReversedReturnedCount(reversed)
                .setAffectedReports(details).setDataGaps(globalGaps).setEvidenceRefs(refs.stream().toList())
                .setTruncated(total > details.size());
    }

    private MesBatchTraceDTO.AffectedReport map(MesTaskReportEntity report) {
        List<String> gaps = new ArrayList<>();
        if (!List.of("EFFECTIVE", "REVERSED").contains(Objects.toString(report.getStatus(), ""))) {
            gaps.add("报工缺少明确有效/冲销状态");
        }
        if (!StringUtils.hasText(report.getRequestId())) gaps.add("报工缺少永久请求号");
        if (!StringUtils.hasText(report.getReportUnit())) gaps.add("报工缺少冻结单位，数量不能跨记录合计");
        return new MesBatchTraceDTO.AffectedReport().setReportId(report.getId())
                .setReportRequestId(report.getRequestId()).setStatus(report.getStatus())
                .setTaskCode(report.getTaskCode()).setOperationTaskCode(report.getProduceTaskCode())
                .setArrangeCode(report.getArrangeCode()).setOutputMaterialCode(report.getMatCode())
                .setOutputMaterialName(report.getMatName()).setReportedQuantity(report.getReportNum())
                .setReportUnit(report.getReportUnit()).setReportMode(report.getReportMode())
                .setTransferCode(report.getTransferCode()).setQualityQuantityStatus(report.getQualityQuantityStatus())
                .setReleasedQuantity(report.getQualityReleasedNum()).setPendingQuantity(report.getQualityPendingNum())
                .setRejectedQuantity(report.getQualityRejectedNum()).setReportTime(report.getReportTime()).setGaps(gaps);
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
