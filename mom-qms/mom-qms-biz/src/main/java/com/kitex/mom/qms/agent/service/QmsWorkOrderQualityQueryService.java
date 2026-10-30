package com.kitex.mom.qms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.qms.agent.dto.QmsWorkOrderQualityDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsCapaEntity;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.enums.InspectionTaskStatusEnum;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.InspectionProductTaskService;
import com.kitex.mom.qms.service.QmsBadInfoService;
import com.kitex.mom.qms.service.QmsCapaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 按生产工单精确读取过程检验和成品检验事实。
 *
 * <p>工单编码只匹配QMS任务冻结的order_no，不按物料、批次或时间窗口猜测关联。
 * 来料检验没有生产工单来源，因此不纳入本工具。</p>
 */
@Service
@RequiredArgsConstructor
public class QmsWorkOrderQualityQueryService {

    public static final String TOOL_CODE = "QMS_WORK_ORDER_QUALITY_QUERY@1";
    private static final int STANDARD_MAX_TASKS = 50;
    private static final int EFFECT_MAX_TASKS = 200;

    private final QmsAgentFactoryAccessService factoryAccess;
    private final PermissionService permissionService;
    private final InspectionProcedureTaskService procedureTaskService;
    private final InspectionProductTaskService productTaskService;
    private final QmsBadInfoService badInfoService;
    private final QmsCapaService capaService;

    public QmsWorkOrderQualityDTO.Evidence query(QmsWorkOrderQualityDTO.Query query) {
        return query(query, false);
    }

    /**
     * 参数异常效果复核需要逐条核对检验任务、同源 NCR 和 CAPA。
     * 单独扩大只读明细范围，不改变面向普通问数的 50 条上限；超过 200 条仍标记截断并阻止结案。
     * 汇总、任务、NCR 和 CAPA 在同一数据库快照下读取，避免并发质检提交导致单次回执自相矛盾。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsWorkOrderQualityDTO.Evidence queryForEffect(QmsWorkOrderQualityDTO.Query query) {
        return query(query, true);
    }

    private QmsWorkOrderQualityDTO.Evidence query(QmsWorkOrderQualityDTO.Query query, boolean effectReview) {
        if (query == null) {
            throw new CheckedException("生产工单质量查询不能为空");
        }
        String factory = factoryAccess.requireRead(query.getFactoryCode());
        String workOrderCode = normalizeCode(query.getWorkOrderCode());
        int limit = effectReview ? EFFECT_MAX_TASKS : query.getLimit() == null ? 20
                : Math.max(1, Math.min(query.getLimit(), STANDARD_MAX_TASKS));
        query.setWorkOrderCode(workOrderCode).setLimit(limit);

        boolean procedurePermitted = permissionService.hasPermission("procedureTask:show");
        boolean productPermitted = permissionService.hasPermission("productTask:show");
        if (!procedurePermitted && !productPermitted) {
            throw new CheckedException("当前岗位无权读取过程检验或成品检验");
        }
        Metrics procedure = procedurePermitted
                ? metrics(procedureTaskService.getBaseMapper(), base(factory, workOrderCode))
                : Metrics.empty();
        Metrics product = productPermitted
                ? metrics(productTaskService.getBaseMapper(), base(factory, workOrderCode))
                : Metrics.empty();
        List<Metrics> metrics = List.of(procedure, product);

        List<QmsWorkOrderQualityDTO.QualityTask> collected = new ArrayList<>();
        if (procedurePermitted) {
            procedureTaskService.list(detailQuery(factory, workOrderCode, limit))
                    .forEach(item -> collected.add(fromProcedure(item)));
        }
        if (productPermitted) {
            productTaskService.list(detailQuery(factory, workOrderCode, limit))
                    .forEach(item -> collected.add(fromProduct(item)));
        }
        List<QmsWorkOrderQualityDTO.QualityTask> tasks = collected.stream()
                .sorted(Comparator.comparing(QmsWorkOrderQualityDTO.QualityTask::getLastInspectionTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(QmsWorkOrderQualityDTO.QualityTask::getCreateTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(QmsWorkOrderQualityDTO.QualityTask::getTaskId,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList();

        long qualified = sumResult(metrics, InspectionTaskResultEnum.QUALIFIED.name());
        long unqualified = sumResult(metrics, InspectionTaskResultEnum.UN_QUALIFIED.name());
        long total = metrics.stream().mapToLong(Metrics::total).sum();
        QmsWorkOrderQualityDTO.Summary summary = new QmsWorkOrderQualityDTO.Summary()
                .setTaskCount(total)
                .setWaitingTaskCount(sumStatus(metrics, InspectionTaskStatusEnum.WAIT.name()))
                .setRunningTaskCount(sumStatus(metrics, InspectionTaskStatusEnum.RUNNING.name()))
                .setUnhandledTaskCount(sumStatus(metrics, InspectionTaskStatusEnum.UNHANDLED.name()))
                .setFinishedTaskCount(sumStatus(metrics, InspectionTaskStatusEnum.FINISHED.name()))
                .setQualifiedTaskCount(qualified)
                .setUnqualifiedTaskCount(unqualified)
                .setExemptionTaskCount(sumResult(metrics, InspectionTaskResultEnum.EXEMPTION.name()))
                .setEmergencyReleaseTaskCount(sumResult(metrics, InspectionTaskResultEnum.RELEASE.name()))
                .setConcessionReceiveTaskCount(sumResult(metrics, InspectionTaskResultEnum.RECEIVE.name()))
                .setCanceledTaskCount(sumResult(metrics, InspectionTaskResultEnum.CANCELED.name()))
                .setStrictTaskQualificationRate(rate(qualified, qualified + unqualified));

        List<String> gaps = new ArrayList<>();
        if (total == 0) {
            gaps.add("该工单没有按order_no精确关联的过程或成品检验任务；不能据此判断工单质量合格");
        }
        if (qualified + unqualified == 0 && total > 0) {
            gaps.add("该工单尚无严格合格/不合格判定，合格率为0不代表质量水平");
        }
        if (total > tasks.size()) {
            gaps.add("该工单共有" + total + "个质量任务，本次按上限返回" + tasks.size() + "个明细");
        }
        if (!procedurePermitted) {
            gaps.add("当前岗位没有过程检验查看权限，本次证据不含过程检验任务");
        }
        if (!productPermitted) {
            gaps.add("当前岗位没有成品检验查看权限，本次证据不含成品检验任务");
        }
        List<String> refs = new ArrayList<>(tasks.stream().map(item -> "QMS:"
                + ("PROCEDURE".equals(item.getInspectionType())
                ? "qms_inspection_procedure_task:" : "qms_inspection_product_task:")
                + item.getTaskId()).toList());

        // NCR只能从本次有权读取、且已按工厂和工单精确核验的检验任务主键反查。
        // 物料/批次相似不构成质量案件的来源证明。
        List<QmsBadInfoEntity> ncrs = List.of();
        List<QmsCapaEntity> capas = List.of();
        boolean ncrTruncated = false;
        if (!permissionService.hasPermission("bad:show")) {
            gaps.add("当前岗位没有NCR查看权限，不能判断该工单是否已有正式不合格品单");
        } else {
            List<Long> procedureIds = tasks.stream().filter(item -> "PROCEDURE".equals(item.getInspectionType()))
                    .map(QmsWorkOrderQualityDTO.QualityTask::getTaskId).toList();
            List<Long> productIds = tasks.stream().filter(item -> "PRODUCT".equals(item.getInspectionType()))
                    .map(QmsWorkOrderQualityDTO.QualityTask::getTaskId).toList();
            if (!procedureIds.isEmpty() || !productIds.isEmpty()) {
                LambdaQueryWrapper<QmsBadInfoEntity> source = new LambdaQueryWrapper<QmsBadInfoEntity>()
                        .eq(QmsBadInfoEntity::getFactoryCode, factory);
                if (!procedureIds.isEmpty() && !productIds.isEmpty()) {
                    source.and(scope -> scope.nested(group -> group.eq(QmsBadInfoEntity::getResourceType, "PROCEDURE")
                                    .in(QmsBadInfoEntity::getResourceTaskId, procedureIds))
                            .or().nested(group -> group.eq(QmsBadInfoEntity::getResourceType, "PRODUCT")
                                    .in(QmsBadInfoEntity::getResourceTaskId, productIds)));
                } else if (!procedureIds.isEmpty()) {
                    source.eq(QmsBadInfoEntity::getResourceType, "PROCEDURE")
                            .in(QmsBadInfoEntity::getResourceTaskId, procedureIds);
                } else {
                    source.eq(QmsBadInfoEntity::getResourceType, "PRODUCT")
                            .in(QmsBadInfoEntity::getResourceTaskId, productIds);
                }
                source.orderByDesc(QmsBadInfoEntity::getCreateTime)
                        .orderByDesc(QmsBadInfoEntity::getId).last("LIMIT " + (EFFECT_MAX_TASKS + 1));
                List<QmsBadInfoEntity> candidates = badInfoService.list(source);
                if (candidates.size() > EFFECT_MAX_TASKS) {
                    ncrTruncated = true;
                    gaps.add("同源NCR超过" + EFFECT_MAX_TASKS + "条，本次仅展示前"
                            + EFFECT_MAX_TASKS + "条；须在原QMS中查看全部案件");
                }
                Map<String, QmsWorkOrderQualityDTO.QualityTask> taskBySource = new HashMap<>();
                tasks.forEach(item -> taskBySource.put(item.getInspectionType() + ":" + item.getTaskId(), item));
                List<QmsBadInfoEntity> accepted = new ArrayList<>();
                for (QmsBadInfoEntity ncr : candidates.stream().limit(EFFECT_MAX_TASKS).toList()) {
                    QmsWorkOrderQualityDTO.QualityTask task = taskBySource.get(
                            ncr.getResourceType() + ":" + ncr.getResourceTaskId());
                    if (task == null || !Objects.equals(ncr.getMatCode(), task.getMaterialCode())
                            || !Objects.equals(ncr.getBatchNo(), task.getBatchNo())) {
                        gaps.add("NCR" + ncr.getId() + "来源任务或物料批次与当前检验快照冲突，未纳入同源案件");
                        continue;
                    }
                    accepted.add(ncr);
                }
                ncrs = List.copyOf(accepted);
                ncrs.forEach(item -> refs.add("QMS:qms_bad_info:" + item.getId()));
            }
            // 原过程/成品检验在判为不合格时由QMS原事务创建NCR。只有任务和案件均未截断，
            // 才能把“找不到同源NCR”识别为需人工核查的数据异常；不能让Agent补造案件。
            if (!ncrTruncated && total == tasks.size()) {
                Set<String> ncrSources = ncrs.stream()
                        .map(item -> item.getResourceType() + ":" + item.getResourceTaskId())
                        .collect(java.util.stream.Collectors.toSet());
                tasks.stream().filter(item -> InspectionTaskResultEnum.UN_QUALIFIED.name()
                                .equals(item.getResult()))
                        .filter(item -> !ncrSources.contains(item.getInspectionType() + ":" + item.getTaskId()))
                        .forEach(item -> gaps.add("不合格检验任务" + item.getInspectionType() + ":" + item.getTaskId()
                                + "未查到同源NCR；请在原QMS核对检验提交与案件/隔离事务，Agent不得补建"));
            }
            if (!permissionService.hasPermission("capa:show")) {
                gaps.add("当前岗位没有CAPA查看权限，不披露关联CAPA状态");
            } else {
                List<Long> capaIds = ncrs.stream().map(QmsBadInfoEntity::getCapaId)
                        .filter(Objects::nonNull).distinct().toList();
                if (!capaIds.isEmpty()) {
                    List<QmsCapaEntity> candidates = capaService.list(new LambdaQueryWrapper<QmsCapaEntity>()
                            .eq(QmsCapaEntity::getFactoryCode, factory)
                            .in(QmsCapaEntity::getId, capaIds));
                    Set<Long> ncrIds = ncrs.stream().map(QmsBadInfoEntity::getId).collect(java.util.stream.Collectors.toSet());
                    capas = candidates.stream().filter(item -> "NCR".equals(item.getSourceType())
                                    && ncrIds.contains(item.getSourceId())).toList();
                    Set<Long> verified = capas.stream().map(QmsCapaEntity::getId)
                            .collect(java.util.stream.Collectors.toSet());
                    ncrs.stream().filter(item -> item.getCapaId() != null && !verified.contains(item.getCapaId()))
                            .forEach(item -> gaps.add("NCR" + item.getId() + "关联的CAPA来源不一致或当前工厂不可读取"));
                    capas.forEach(item -> refs.add("QMS:qms_capa:" + item.getId()));
                }
            }
        }

        return new QmsWorkOrderQualityDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("仅纳入当前岗位有权查看且factory_code和order_no同时精确匹配的过程/成品检验任务；NCR仅按这些任务的来源类型和主键反查，CAPA再按NCR当前关联主键及来源核对。状态均为观察时刻快照；严格任务合格率=合格任务数/(合格任务数+不合格任务数)，免检、紧急放行、让步接收和作废分别披露。")
                .setFactoryCode(factory).setWorkOrderCode(workOrderCode)
                .setObservedAt(LocalDateTime.now()).setSummary(summary).setTasks(tasks)
                .setNonconformances(ncrs.stream().map(this::ncr).toList())
                .setCapas(capas.stream().map(this::capa).toList())
                .setDataGaps(gaps).setEvidenceRefs(refs).setTruncated(total > tasks.size() || ncrTruncated);
    }

    private QmsWorkOrderQualityDTO.Nonconformance ncr(QmsBadInfoEntity item) {
        return new QmsWorkOrderQualityDTO.Nonconformance()
                .setNcrId(String.valueOf(item.getId())).setNcrNo(item.getNcrNo())
                .setSourceType(item.getResourceType()).setSourceTaskId(String.valueOf(item.getResourceTaskId()))
                .setStatus(item.getStatus()).setMaterialCode(item.getMatCode()).setBatchNo(item.getBatchNo())
                .setCapaId(item.getCapaId() == null ? null : String.valueOf(item.getCapaId()))
                .setCapaStatus(item.getCapaStatus());
    }

    private QmsWorkOrderQualityDTO.Capa capa(QmsCapaEntity item) {
        return new QmsWorkOrderQualityDTO.Capa()
                .setCapaId(String.valueOf(item.getId())).setCapaNo(item.getCapaNo())
                .setSourceNcrId(String.valueOf(item.getSourceId()))
                .setStatus(item.getStatus()).setEffectivenessResult(item.getEffectivenessResult())
                .setClosedTime(item.getClosedTime());
    }

    private <T> Metrics metrics(BaseMapper<T> mapper, QueryWrapper<T> scope) {
        List<Map<String, Object>> rows = mapper.selectMaps(scope
                .select("COALESCE(NULLIF(status, ''), 'UNKNOWN') AS status",
                        "COALESCE(NULLIF(result, ''), 'UNKNOWN') AS result", "COUNT(*) AS task_count")
                .groupBy("COALESCE(NULLIF(status, ''), 'UNKNOWN')",
                        "COALESCE(NULLIF(result, ''), 'UNKNOWN')"));
        Map<String, Long> statuses = new HashMap<>();
        Map<String, Long> results = new HashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            long count = number(row.get("task_count"));
            statuses.merge(text(row.get("status")), count, Long::sum);
            results.merge(text(row.get("result")), count, Long::sum);
            total += count;
        }
        return new Metrics(total, statuses, results);
    }

    private <T> QueryWrapper<T> base(String factory, String workOrderCode) {
        return new QueryWrapper<T>().eq("factory_code", factory).eq("order_no", workOrderCode);
    }

    private <T> QueryWrapper<T> detailQuery(String factory, String workOrderCode, int limit) {
        return this.<T>base(factory, workOrderCode)
                .last("ORDER BY latest_inspection_time DESC NULLS LAST, create_time DESC, id DESC LIMIT " + limit);
    }

    private QmsWorkOrderQualityDTO.QualityTask fromProcedure(QmsInspectionProcedureTaskEntity item) {
        return task("PROCEDURE", item.getId(), item.getSourceTaskCode(), item.getOrderNo(),
                item.getMatCode(), item.getMatName(), item.getBatchNo(), item.getProcessCode(),
                item.getProcessName(), item.getStatus(), item.getResult(), item.getInspectionNum(),
                item.getUsableNum(), item.getHandleNum(), item.getLatestInspectionTime(), item.getCreateTime());
    }

    private QmsWorkOrderQualityDTO.QualityTask fromProduct(QmsInspectionProductTaskEntity item) {
        return task("PRODUCT", item.getId(), item.getSourceTaskCode(), item.getOrderNo(),
                item.getMatCode(), item.getMatName(), item.getBatchNo(), item.getProcessCode(),
                item.getProcessName(), item.getStatus(), item.getResult(), item.getInspectionNum(),
                item.getUsableNum(), item.getHandleNum(), item.getLatestInspectionTime(), item.getCreateTime());
    }

    private QmsWorkOrderQualityDTO.QualityTask task(String type, Long id, String sourceTaskCode,
                                                    String workOrderCode, String materialCode,
                                                    String materialName, String batchNo,
                                                    String processCode, String processName,
                                                    String status, String result,
                                                    Integer inspectionQuantity, Integer usableQuantity,
                                                    Integer handledQuantity, Date inspectionTime,
                                                    LocalDateTime createTime) {
        return new QmsWorkOrderQualityDTO.QualityTask()
                .setInspectionType(type).setTaskId(id).setSourceTaskCode(sourceTaskCode)
                .setWorkOrderCode(workOrderCode).setMaterialCode(materialCode)
                .setMaterialName(materialName).setBatchNo(batchNo)
                .setProcessCode(processCode).setProcessName(processName)
                .setStatus(status).setStatusName(statusName(status))
                .setResult(result).setResultName(resultName(result))
                .setInspectionQuantity(inspectionQuantity).setUsableQuantity(usableQuantity)
                .setHandledQuantity(handledQuantity).setLastInspectionTime(toLocalDateTime(inspectionTime))
                .setCreateTime(createTime);
    }

    private long sumResult(List<Metrics> all, String result) {
        return all.stream().mapToLong(item -> item.results().getOrDefault(result, 0L)).sum();
    }

    private long sumStatus(List<Metrics> all, String status) {
        return all.stream().mapToLong(item -> item.statuses().getOrDefault(status, 0L)).sum();
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new CheckedException("生产工单号不能为空");
        }
        String normalized = code.trim();
        if (normalized.length() > 100) {
            throw new CheckedException("生产工单号不能超过100位");
        }
        return normalized;
    }

    private String statusName(String status) {
        try {
            return InspectionTaskStatusEnum.valueOf(status).getName();
        } catch (Exception ignored) {
            return "未识别状态";
        }
    }

    private String resultName(String result) {
        if (result == null) {
            return "未判定";
        }
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

    private LocalDateTime toLocalDateTime(Date value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault());
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private record Metrics(long total, Map<String, Long> statuses, Map<String, Long> results) {
        private static Metrics empty() {
            return new Metrics(0L, Map.of(), Map.of());
        }
    }
}
