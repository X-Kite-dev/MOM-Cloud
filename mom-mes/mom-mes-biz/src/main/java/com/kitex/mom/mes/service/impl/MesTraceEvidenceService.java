package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;
import com.kitex.mom.mes.service.MesTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 实际消耗追溯只读工作台。不以当前BOM推测历史批次，不把包装历史行累加为现存量。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("@pms.hasPermission('mes_traceEvidence_view')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MesTraceEvidenceService {
    private final MesTraceEvidenceMapper reports;
    private final MesReportMaterialConsumeMapper consumes;
    private final MesWarehouseInoutMapper inouts;
    private final MesWarehouseFeedMapper feeds;
    private final MesWrapRecordMapper wraps;
    private final MesTaskMapper tasks;
    private final MesProduceTaskMapper processes;
    private final MesTaskArrangeMapper arranges;
    private final MesTaskReportRecordMapper records;
    private final MesIdentifierRouteReturnMapper routeReturns;
    private final MesCarrierOperationMapper carrierOperations;
    private final MesTaskService taskService;
    private final RemoteMdmFactoryAccessService factories;

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    public Page<MesTaskReportEntity> search(String kind, String code, String batch, long current) {
        String factory = authorize();
        require(Set.of("TASK", "TRANSFER", "REPORT", "WRAP", "CARRIER", "MATERIAL").contains(kind), "请选择精确追溯类型");
        require(StrUtil.isNotBlank(code) && code.length() <= 200, "请输入1至200字的完整编码");
        require("MATERIAL".equals(kind) ? StrUtil.isNotBlank(batch) && batch.length() <= 200 : StrUtil.isBlank(batch), "物料追溯必须提供物料编码和批次；其他类型不能夹带批次条件");
        Long id = null;
        if ("REPORT".equals(kind)) {
            try {
                id = Long.valueOf(code.trim());
            } catch (NumberFormatException e) {
                throw new CheckedException("报工ID必须为完整整数");
            }
            require(id > 0, "报工ID不合法");
        }
        return reports.trace(page(current), tenant(), factory, kind, code.trim(), batch == null ? null : batch.trim(), id);
    }

    public Map<String, Object> detail(Long id) {
        var report = report(id);
        String factory = report.getFactoryCode();
        List<String> gaps = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", report);
        result.put("observedAt", LocalDateTime.now());
        var task = StrUtil.isBlank(report.getTaskCode()) ? List.<MesTaskEntity>of() : tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, factory).eq(MesTaskEntity::getCode, report.getTaskCode()).last("LIMIT 2"));
        if (task.size() == 1) result.put("task", task.get(0));
        else gaps.add("工单缺失或编码不唯一，不能推断订单/PLAN归属");
        var process = StrUtil.isBlank(report.getProduceTaskCode()) ? List.<MesProduceTaskEntity>of() : processes.selectList(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .eq(MesProduceTaskEntity::getFactoryCode, factory).eq(MesProduceTaskEntity::getCode, report.getProduceTaskCode()).last("LIMIT 2"));
        boolean processValid = process.size() == 1 && Objects.equals(process.get(0).getTaskCode(), report.getTaskCode());
        if (processValid) result.put("process", process.get(0));
        else gaps.add("工序任务缺失、编码歧义或工单归属不一致");
        var arrange = StrUtil.isBlank(report.getArrangeCode()) ? List.<MesTaskArrangeEntity>of() : arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getFactoryCode, factory).eq(MesTaskArrangeEntity::getCode, report.getArrangeCode()).last("LIMIT 2"));
        if (arrange.size() == 1 && Objects.equals(arrange.get(0).getTaskCode(), report.getTaskCode())
                && Objects.equals(arrange.get(0).getProduceTaskCode(), report.getProduceTaskCode()))
            result.put("arrange", arrange.get(0));
        else gaps.add("派工缺失、编码歧义或工序归属不一致；计划资源不能冒充实际执行资源");
        if (task.size() == 1 && processValid) try {
            var mapping = taskService.planSourceMapping(report.getTaskCode(), report.getProduceTaskCode(), factory);
            result.put("planMapping", mapping);
            if (!Boolean.TRUE.equals(mapping.getMappingComplete())) gaps.add(mapping.getMappingMessage());
        } catch (CheckedException ex) {
            gaps.add("来源计划映射不可核验：" + ex.getMessage());
        }
        if (!known(report.getStatus()) || StrUtil.isBlank(report.getRequestId()))
            gaps.add("历史报工缺少明确有效/冲销状态或永久请求号，不认定为完整证据");
        if (MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(report.getExecutionKind())) {
            MesIdentifierRouteReturnEntity routeReturn = report.getRouteReturnId() == null ? null
                    : routeReturns.selectOne(new LambdaQueryWrapper<MesIdentifierRouteReturnEntity>()
                    .eq(MesIdentifierRouteReturnEntity::getId, report.getRouteReturnId())
                    .eq(MesIdentifierRouteReturnEntity::getFactoryCode, factory));
            if (routeReturn == null || !Objects.equals(routeReturn.getVersionNo(), report.getIdentifierRouteVersion())) {
                gaps.add("退回路线申请缺失或报工路线版本不一致");
            } else {
                result.put("routeReturn", routeReturn);
            }
        }
        if (report.getCarrierOperationId() != null) {
            MesCarrierOperationEntity carrierOperation = carrierOperations.selectOne(
                    new LambdaQueryWrapper<MesCarrierOperationEntity>()
                            .eq(MesCarrierOperationEntity::getId, report.getCarrierOperationId())
                            .eq(MesCarrierOperationEntity::getFactoryCode, factory));
            if (carrierOperation == null || !Objects.equals(report.getCarrierCode(), carrierOperation.getCarrierCode())
                    || !"REPORT_EXPAND".equals(carrierOperation.getOperationType())) {
                gaps.add("载具展开回执缺失或与逐件报工关联不一致");
            } else {
                result.put("carrierOperation", carrierOperation);
            }
        }
        long manual = consumes.selectCount(new LambdaQueryWrapper<MesReportMaterialConsumeEntity>().eq(MesReportMaterialConsumeEntity::getReportId, id).eq(MesReportMaterialConsumeEntity::getFactoryCode, factory));
        long backflush = inouts.selectCount(new LambdaQueryWrapper<MesWarehouseInoutEntity>().eq(MesWarehouseInoutEntity::getBillId, id).eq(MesWarehouseInoutEntity::getBizType, "back_flush_out").eq(MesWarehouseInoutEntity::getFactoryCode, factory));
        result.put("manualCount", manual);
        result.put("backflushCount", backflush);
        if (manual + backflush == 0
                && !MesIdentifierRouteReturnCoordinator.ROUTE_RETURN.equals(report.getExecutionKind())) {
            gaps.add("未找到实际消耗证据；不能据此认定未用料，可能是历史缺账或无耗料工序");
        }
        result.put("gaps", gaps);
        return result;
    }

    public Map<String, Object> materials(Long id, String kind, long current) {
        var report = report(id);
        String factory = report.getFactoryCode();
        List<Map<String, Object>> rows = new ArrayList<>();
        long total;
        if ("MANUAL".equals(kind)) {
            var data = consumes.selectPage(page(current), new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
                    .eq(MesReportMaterialConsumeEntity::getReportId, id).eq(MesReportMaterialConsumeEntity::getFactoryCode, factory).orderByAsc(MesReportMaterialConsumeEntity::getId));
            total = data.getTotal();
            var feedIds = data.getRecords().stream().map(MesReportMaterialConsumeEntity::getFeedId).filter(Objects::nonNull).distinct().toList();
            Map<Long, MesWarehouseFeedEntity> feedMap = new HashMap<>();
            if (!feedIds.isEmpty())
                feeds.selectList(new LambdaQueryWrapper<MesWarehouseFeedEntity>().in(MesWarehouseFeedEntity::getId, feedIds)
                        .eq(MesWarehouseFeedEntity::getFactoryCode, factory)).forEach(f -> feedMap.put(f.getId(), f));
            for (var c : data.getRecords()) {
                List<String> gaps = new ArrayList<>();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("evidence", c);
                var feed = feedMap.get(c.getFeedId());
                if (feed == null) gaps.add("原投料记录缺失或不在授权工厂");
                else if (!Objects.equals(feed.getTaskCode(), report.getTaskCode()) || !Objects.equals(feed.getProduceTaskCode(), report.getProduceTaskCode())
                        || !Objects.equals(feed.getArrangeCode(), report.getArrangeCode()) || !Objects.equals(feed.getMatCode(), c.getMatCode())
                        || !Objects.equals(feed.getMatBatch(), c.getMatBatch()) || !Objects.equals(feed.getMatBarcode(), c.getMatBarcode()))
                    gaps.add("投料与消耗的工单/工序/派工/实际物料/批次/条码不一致");
                else item.put("feed", feed);
                if (StrUtil.isBlank(report.getRequestId()) || !Objects.equals(c.getReportRequestId(), report.getRequestId()) || !Objects.equals(c.getArrangeCode(), report.getArrangeCode()))
                    gaps.add("消耗与报工请求或派工不一致");
                if (!known(c.getStatus()) || !Objects.equals(c.getStatus(), report.getStatus()))
                    gaps.add("消耗与报工有效/冲销状态不可核验");
                if ("REVERSED".equals(c.getStatus()) && (StrUtil.isBlank(c.getReverseRequestId()) || !Objects.equals(c.getReverseRequestId(), report.getReverseRequestId())))
                    gaps.add("消耗冲销请求与报工冲销不一致");
                if (c.getConsumeNum() == null || c.getConsumeNum().signum() <= 0 || c.getSubstituteRatio() == null || c.getSubstituteRatio().signum() <= 0)
                    gaps.add("实际消耗数量或替代比例无效");
                item.put("gaps", gaps);
                rows.add(item);
            }
        } else {
            require("BACKFLUSH".equals(kind) || "RESTORE".equals(kind), "用料证据类型不合法");
            boolean restore = "RESTORE".equals(kind);
            var data = inouts.selectPage(page(current), new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                    .eq(MesWarehouseInoutEntity::getBillId, id).eq(MesWarehouseInoutEntity::getFactoryCode, factory)
                    .eq(MesWarehouseInoutEntity::getBizType, restore ? "report_reverse_material_in" : "back_flush_out").orderByAsc(MesWarehouseInoutEntity::getId));
            total = data.getTotal();
            for (var i : data.getRecords()) {
                List<String> gaps = new ArrayList<>();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("evidence", i);
                String request = restore ? report.getReverseRequestId() : report.getRequestId();
                if (StrUtil.isBlank(request) || !Objects.equals(i.getResourceNo(), request) || !Objects.equals(i.getBillNo(), report.getArrangeCode()))
                    gaps.add("库存流水与报工/冲销请求或派工不一致");
                if (!known(report.getStatus()) || (restore && !"REVERSED".equals(report.getStatus())))
                    gaps.add("报工终态与恢复流水不可核验");
                if (i.getOperateNum() == null || i.getOperateNum().signum() <= 0) gaps.add("库存流水数量无效");
                item.put("gaps", gaps);
                rows.add(item);
            }
        }
        return Map.of("records", rows, "total", total, "current", current, "reportStatus", Objects.toString(report.getStatus(), "UNKNOWN"), "observedAt", LocalDateTime.now());
    }

    public Map<String, Object> packages(Long id, long current) {
        var report = report(id);
        var data = wraps.selectPage(page(current), new LambdaQueryWrapper<MesWrapRecordEntity>()
                .eq(MesWrapRecordEntity::getReportId, id).eq(MesWrapRecordEntity::getFactoryCode, report.getFactoryCode()).orderByAsc(MesWrapRecordEntity::getId));
        var parentIds = data.getRecords().stream().map(MesWrapRecordEntity::getSourceRecordId).filter(Objects::nonNull).distinct().toList();
        Map<Long, MesWrapRecordEntity> parents = new HashMap<>();
        if (!parentIds.isEmpty())
            wraps.selectList(new LambdaQueryWrapper<MesWrapRecordEntity>().in(MesWrapRecordEntity::getId, parentIds)
                    .eq(MesWrapRecordEntity::getFactoryCode, report.getFactoryCode())).forEach(w -> parents.put(w.getId(), w));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var w : data.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("content", w);
            List<String> gaps = new ArrayList<>();
            if (!Objects.equals(w.getTaskCode(), report.getTaskCode()) || !Objects.equals(w.getProcedureTaskCode(), report.getProduceTaskCode())
                    || !Objects.equals(w.getArrangeCode(), report.getArrangeCode()))
                gaps.add("包装与报工的工单/工序/派工关联不一致");
            if (w.getSourceRecordId() != null) {
                var parent = parents.get(w.getSourceRecordId());
                if (parent == null || Objects.equals(w.getId(), w.getSourceRecordId()) || !Objects.equals(parent.getReportId(), id))
                    gaps.add("重组来源缺失、自引用、跨报工或不在授权工厂");
                else row.put("parent", parent);
            }
            if (w.getPackingStatus() == null || !Set.of("STAGED", "PACKED", "UNPACKED", "PARTIAL", "MOVED", "REVERSED").contains(w.getPackingStatus()))
                gaps.add("包装历史状态未知");
            if (w.getQuantity() == null || w.getQuantity().signum() <= 0) gaps.add("包装原始数量无效");
            row.put("gaps", gaps);
            rows.add(row);
        }
        return Map.of("records", rows, "total", data.getTotal(), "current", current, "observedAt", LocalDateTime.now());
    }

    public Page<MesTaskReportRecordEntity> events(Long id, long current) {
        var report = report(id);
        return records.selectPage(page(current), new LambdaQueryWrapper<MesTaskReportRecordEntity>()
                .eq(MesTaskReportRecordEntity::getReportId, id).eq(MesTaskReportRecordEntity::getFactoryCode, report.getFactoryCode())
                .orderByAsc(MesTaskReportRecordEntity::getId));
    }

    private MesTaskReportEntity report(Long id) {
        String factory = authorize();
        var report = id == null ? null : reports.selectById(id);
        require(report != null && Objects.equals(report.getTenantId(), tenant()) && Objects.equals(report.getFactoryCode(), factory), "报工不存在或不在授权实际工厂");
        return report;
    }

    private String authorize() {
        require(tenant() != null && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                && SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null, "追溯须登录且不能跳过租户/工厂隔离");
        String factory = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isNotBlank(factory), "请选择实际工厂");
        var r = factories.check(new MdmFactoryAccessCheck().setUserId(SecurityUtils.getUser().getId()).setFactoryCode(factory).setRequiredPermission("read"));
        require(r != null && r.isOk() && Boolean.TRUE.equals(r.getData()), "缺少实际工厂读取权限或MDM不可核验");
        return factory;
    }

    private Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private boolean known(String state) {
        return "EFFECTIVE".equals(state) || "REVERSED".equals(state);
    }

    private <T> Page<T> page(long current) {
        require(current >= 1 && current <= 100000, "页码须在1至100000内");
        return new Page<>(current, 20);
    }
}
