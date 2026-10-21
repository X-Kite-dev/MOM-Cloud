package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.agent.dto.QmsInspectionOverviewDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.enums.InspectionTaskStatusEnum;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.InspectionProductTaskService;
import com.kitex.mom.qms.service.InspectionPurchaseTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 来料、过程和成品检验统一概览。
 *
 * <p>统计范围是查询窗口内新建、追加或完成过检验的任务，结果采用当前任务快照。
 * 合格率使用严格任务口径：合格/(合格+不合格)，免检、紧急放行、让步接收和作废分别披露。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsInspectionOverviewQueryService {

    public static final String TOOL_CODE = "QMS_INSPECTION_OVERVIEW_QUERY@1";
    private static final int MAX_RANGE_DAYS = 31;

    private final QmsAgentFactoryAccessService factoryAccess;
    private final InspectionProcedureTaskService procedureTaskService;
    private final InspectionProductTaskService productTaskService;
    private final InspectionPurchaseTaskService purchaseTaskService;

    public QmsInspectionOverviewDTO.Evidence query(QmsInspectionOverviewDTO.Query query) {
        if (query == null) throw new CheckedException("质量概览查询不能为空");
        String factory = factoryAccess.requireRead(query.getFactoryCode());
        QueryRange range = normalize(query);
        Metrics procedure = metrics("PROCEDURE", "过程检验", procedureTaskService.getBaseMapper(),
                activity(new QueryWrapper<QmsInspectionProcedureTaskEntity>(), factory, range));
        Metrics product = metrics("PRODUCT", "成品检验", productTaskService.getBaseMapper(),
                activity(new QueryWrapper<QmsInspectionProductTaskEntity>(), factory, range));
        Metrics purchase = metrics("PURCHASE", "来料检验", purchaseTaskService.getBaseMapper(),
                activity(new QueryWrapper<QmsInspectionPurchaseTaskEntity>(), factory, range));
        List<Metrics> all = List.of(procedure, product, purchase);

        List<QmsInspectionOverviewDTO.ExceptionTask> collectedExceptions = new ArrayList<>();
        procedureTaskService.list(exceptionQuery(new QueryWrapper<QmsInspectionProcedureTaskEntity>(), factory, range, query.getLimit()))
                .forEach(item -> collectedExceptions.add(fromProcedure(item)));
        productTaskService.list(exceptionQuery(new QueryWrapper<QmsInspectionProductTaskEntity>(), factory, range, query.getLimit()))
                .forEach(item -> collectedExceptions.add(fromProduct(item)));
        purchaseTaskService.list(exceptionQuery(new QueryWrapper<QmsInspectionPurchaseTaskEntity>(), factory, range, query.getLimit()))
                .forEach(item -> collectedExceptions.add(fromPurchase(item)));
        List<QmsInspectionOverviewDTO.ExceptionTask> exceptions = collectedExceptions.stream()
                .sorted(Comparator.comparing(QmsInspectionOverviewDTO.ExceptionTask::getLastInspectionTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(QmsInspectionOverviewDTO.ExceptionTask::getCreateTime,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(query.getLimit()).toList();

        long qualified = sum(all, InspectionTaskResultEnum.QUALIFIED.name());
        long unqualified = sum(all, InspectionTaskResultEnum.UN_QUALIFIED.name());
        long exceptionCount = all.stream().mapToLong(Metrics::exceptionCount).sum();
        QmsInspectionOverviewDTO.Summary summary = new QmsInspectionOverviewDTO.Summary()
                .setActivityTaskCount(all.stream().mapToLong(Metrics::total).sum())
                .setWaitingTaskCount(sumStatus(all, InspectionTaskStatusEnum.WAIT.name()))
                .setRunningTaskCount(sumStatus(all, InspectionTaskStatusEnum.RUNNING.name()))
                .setUnhandledTaskCount(sumStatus(all, InspectionTaskStatusEnum.UNHANDLED.name()))
                .setFinishedTaskCount(sumStatus(all, InspectionTaskStatusEnum.FINISHED.name()))
                .setQualifiedTaskCount(qualified)
                .setUnqualifiedTaskCount(unqualified)
                .setExemptionTaskCount(sum(all, InspectionTaskResultEnum.EXEMPTION.name()))
                .setEmergencyReleaseTaskCount(sum(all, InspectionTaskResultEnum.RELEASE.name()))
                .setConcessionReceiveTaskCount(sum(all, InspectionTaskResultEnum.RECEIVE.name()))
                .setCanceledTaskCount(sum(all, InspectionTaskResultEnum.CANCELED.name()))
                .setStrictTaskQualificationRate(rate(qualified, qualified + unqualified));
        List<String> gaps = new ArrayList<>();
        gaps.add("检验任务未冻结统一物料单位，本场景只汇总任务数，不跨物料累计检验数量");
        if (qualified + unqualified == 0)
            gaps.add("查询窗口内没有严格合格/不合格判定，严格任务合格率为0且不代表质量水平");
        if (exceptionCount > exceptions.size())
            gaps.add("异常任务共" + exceptionCount + "个，本次按上限返回" + exceptions.size() + "个");
        List<String> refs = exceptions.stream()
                .map(item -> "QMS:" + tableName(item.getInspectionType()) + ":" + item.getTaskId()).toList();

        return new QmsInspectionOverviewDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("纳入查询窗口内新建、追加或完成检验的来料/过程/成品任务，状态和结果取观察时刻当前快照；严格任务合格率=合格任务数/(合格任务数+不合格任务数)，不把免检、紧急放行、让步接收或作废算作合格。")
                .setFactoryCode(factory).setRangeStart(range.start()).setRangeEnd(range.end())
                .setObservedAt(LocalDateTime.now()).setSummary(summary)
                .setTypeSummaries(all.stream().map(this::toTypeSummary).toList())
                .setExceptionTasks(exceptions).setDataGaps(gaps).setEvidenceRefs(refs)
                .setTruncated(exceptionCount > exceptions.size());
    }

    private <T> Metrics metrics(String type, String name, BaseMapper<T> mapper, QueryWrapper<T> scope) {
        List<Map<String, Object>> rows = mapper.selectMaps(scope
                .select("COALESCE(NULLIF(status, ''), 'UNKNOWN') AS status",
                        "COALESCE(NULLIF(result, ''), 'UNKNOWN') AS result", "COUNT(*) AS task_count")
                .groupBy("COALESCE(NULLIF(status, ''), 'UNKNOWN')", "COALESCE(NULLIF(result, ''), 'UNKNOWN')"));
        Map<String, Long> statuses = new HashMap<>();
        Map<String, Long> results = new HashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            long count = number(row.get("task_count"));
            statuses.merge(text(row.get("status")), count, Long::sum);
            results.merge(text(row.get("result")), count, Long::sum);
            total += count;
        }
        long exceptionCount = rows.stream()
                .filter(row -> InspectionTaskResultEnum.UN_QUALIFIED.name().equals(text(row.get("result")))
                        || InspectionTaskStatusEnum.UNHANDLED.name().equals(text(row.get("status"))))
                .mapToLong(row -> number(row.get("task_count"))).sum();
        return new Metrics(type, name, total, statuses, results, exceptionCount);
    }

    private <T> QueryWrapper<T> activity(QueryWrapper<T> wrapper, String factory, QueryRange range) {
        return wrapper.eq("factory_code", factory).and(time -> time
                .and(created -> created.ge("create_time", range.start()).lt("create_time", range.end()))
                .or(inspected -> inspected.ge("latest_inspection_time", range.start()).lt("latest_inspection_time", range.end()))
                .or(appended -> appended.ge("latest_append_time", range.start()).lt("latest_append_time", range.end())));
    }

    private <T> QueryWrapper<T> exceptionQuery(QueryWrapper<T> wrapper, String factory,
                                               QueryRange range, int limit) {
        return activity(wrapper, factory, range)
                .and(exception -> exception.eq("result", InspectionTaskResultEnum.UN_QUALIFIED.name())
                        .or().eq("status", InspectionTaskStatusEnum.UNHANDLED.name()))
                .last("ORDER BY latest_inspection_time DESC NULLS LAST, create_time DESC, id DESC LIMIT " + limit);
    }

    private QmsInspectionOverviewDTO.ExceptionTask fromProcedure(QmsInspectionProcedureTaskEntity item) {
        return base("PROCEDURE", item.getId(), item.getSourceTaskCode(), item.getOrderNo(), item.getMatCode(),
                item.getMatName(), item.getBatchNo(), null, null, item.getProcessCode(), item.getProcessName(),
                item.getStatus(), item.getResult(), item.getInspectionNum(), item.getUsableNum(), item.getHandleNum(),
                item.getLatestInspectionTime(), item.getCreateTime());
    }

    private QmsInspectionOverviewDTO.ExceptionTask fromProduct(QmsInspectionProductTaskEntity item) {
        return base("PRODUCT", item.getId(), item.getSourceTaskCode(), item.getOrderNo(), item.getMatCode(),
                item.getMatName(), item.getBatchNo(), null, null, item.getProcessCode(), item.getProcessName(),
                item.getStatus(), item.getResult(), item.getInspectionNum(), item.getUsableNum(), item.getHandleNum(),
                item.getLatestInspectionTime(), item.getCreateTime());
    }

    private QmsInspectionOverviewDTO.ExceptionTask fromPurchase(QmsInspectionPurchaseTaskEntity item) {
        return base("PURCHASE", item.getId(), item.getTaskCode(), null, item.getMatCode(), item.getMatName(),
                item.getBatchNo(), item.getSupplierCode(), item.getSupplierName(), null, null, item.getStatus(),
                item.getResult(), item.getInspectionNum(), item.getUsableNum(), item.getHandleNum(),
                item.getLatestInspectionTime(), item.getCreateTime());
    }

    private QmsInspectionOverviewDTO.ExceptionTask base(String type, Long id, String code, String orderNo,
                                                        String matCode, String matName, String batchNo,
                                                        String supplierCode, String supplierName,
                                                        String processCode, String processName, String status,
                                                        String result, Integer inspectionNum, Integer usableNum,
                                                        Integer handleNum, Date inspectionTime,
                                                        LocalDateTime createTime) {
        return new QmsInspectionOverviewDTO.ExceptionTask().setInspectionType(type).setTaskId(id)
                .setTaskCode(code).setOrderNo(orderNo).setMaterialCode(matCode).setMaterialName(matName)
                .setBatchNo(batchNo).setSupplierCode(supplierCode).setSupplierName(supplierName)
                .setProcessCode(processCode).setProcessName(processName).setStatus(status)
                .setStatusName(statusName(status)).setResult(result).setResultName(resultName(result))
                .setInspectionQuantity(inspectionNum).setUsableQuantity(usableNum).setHandledQuantity(handleNum)
                .setLastInspectionTime(toLocalDateTime(inspectionTime)).setCreateTime(createTime);
    }

    private QmsInspectionOverviewDTO.TypeSummary toTypeSummary(Metrics item) {
        return new QmsInspectionOverviewDTO.TypeSummary().setInspectionType(item.type())
                .setInspectionTypeName(item.name()).setActivityTaskCount(item.total())
                .setWaitingTaskCount(item.statuses().getOrDefault(InspectionTaskStatusEnum.WAIT.name(), 0L))
                .setRunningTaskCount(item.statuses().getOrDefault(InspectionTaskStatusEnum.RUNNING.name(), 0L))
                .setUnhandledTaskCount(item.statuses().getOrDefault(InspectionTaskStatusEnum.UNHANDLED.name(), 0L))
                .setFinishedTaskCount(item.statuses().getOrDefault(InspectionTaskStatusEnum.FINISHED.name(), 0L))
                .setQualifiedTaskCount(item.results().getOrDefault(InspectionTaskResultEnum.QUALIFIED.name(), 0L))
                .setUnqualifiedTaskCount(item.results().getOrDefault(InspectionTaskResultEnum.UN_QUALIFIED.name(), 0L));
    }

    private QueryRange normalize(QmsInspectionOverviewDTO.Query query) {
        LocalDate today = LocalDate.now();
        LocalDateTime start = query.getStartTime() == null ? today.atStartOfDay() : query.getStartTime();
        LocalDateTime end = query.getEndTime() == null ? today.plusDays(1).atStartOfDay() : query.getEndTime();
        if (!end.isAfter(start)) throw new CheckedException("质量概览查询结束时间必须晚于开始时间");
        if (end.isAfter(start.plusDays(MAX_RANGE_DAYS))) throw new CheckedException("质量概览单次查询区间不能超过31天");
        query.setLimit(query.getLimit() == null ? 20 : Math.max(1, Math.min(query.getLimit(), 50)));
        return new QueryRange(start, end);
    }

    private long sum(List<Metrics> all, String result) {
        return all.stream().mapToLong(item -> item.results().getOrDefault(result, 0L)).sum();
    }

    private long sumStatus(List<Metrics> all, String status) {
        return all.stream().mapToLong(item -> item.statuses().getOrDefault(status, 0L)).sum();
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private String statusName(String status) {
        try {
            return InspectionTaskStatusEnum.valueOf(status).getName();
        } catch (Exception ignored) {
            return "未识别状态";
        }
    }

    private String resultName(String result) {
        if (result == null) return "未判定";
        return switch (result) {
            case "QUALIFIED" -> "合格";
            case "UN_QUALIFIED" -> "不合格";
            case "CANCELED" -> "作废";
            case "EXEMPTION" -> "免检";
            case "RELEASE" -> "紧急放行";
            case "RECEIVE" -> "让步接收";
            default -> "未识别结果";
        };
    }

    private String tableName(String type) {
        return switch (type) {
            case "PROCEDURE" -> "qms_inspection_procedure_task";
            case "PRODUCT" -> "qms_inspection_product_task";
            case "PURCHASE" -> "qms_inspection_purchase_task";
            default -> "inspection_task";
        };
    }

    private LocalDateTime toLocalDateTime(Date value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault());
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private record QueryRange(LocalDateTime start, LocalDateTime end) {
    }

    private record Metrics(String type, String name, long total, Map<String, Long> statuses,
                           Map<String, Long> results, long exceptionCount) {
    }
}
