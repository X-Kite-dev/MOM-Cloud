package com.kitex.mom.mes.controller;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesTaskReportRecordEntity;
import com.kitex.mom.mes.enums.ReportRecordTypeEnum;
import com.kitex.mom.mes.service.MesTaskReportRecordService;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产报工-操作明细
 *
 * @author kitex
 * @since 2025-05-06
 */
@Tag(description = "mesTaskReportRecord", name = "生产报工-操作明细")
@RestController
@RequestMapping("/mesTask/report/record")
@RequiredArgsConstructor
public class MesTaskReportRecordController {

    private final MesTaskReportRecordService service;
    private final MesTaskReportService reportService;
    private final MesActualFactoryAccessService factoryAccess;

    @Operation(summary = "生产报工-操作明细", description = "生产报工-操作明细")
    @GetMapping("/queryRecord")
    public R<List<MesTaskReportRecordEntity>> queryRecord(String arrangeCode) {
        String factory = factoryAccess.require("read");
        List<MesTaskReportRecordEntity> records = service.list(new LambdaQueryWrapper<MesTaskReportRecordEntity>()
                .eq(MesTaskReportRecordEntity::getFactoryCode, factory)
                .eq(MesTaskReportRecordEntity::getArrangeCode, arrangeCode));
        enrichCurrentReportState(records, factory);
        return R.ok(records);
    }


    @Operation(summary = "报工记录分页查询", description = "报工记录分页查询")
    @GetMapping("/page")
    public R<IPage<MesTaskReportRecordEntity>> page(Page<MesTaskReportRecordEntity> pageParam, MesTaskReportRecordEntity query) {
        String factory = factoryAccess.require("read");
        QueryWrapper<MesTaskReportRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.lambda()
                .eq(MesTaskReportRecordEntity::getFactoryCode, factory)
                .orderByDesc(MesTaskReportRecordEntity::getReportTime);
        IPage<MesTaskReportRecordEntity> page = service.page(pageParam, autoQueryWrapper);
        enrichCurrentReportState(page.getRecords(), factory);
        return R.ok(page);
    }

    /**
     * 原报工流水是不可变的业务发生快照，但列表还需要展示报工当前有效量。
     * 这里只在返回对象上回填主报工的实时状态，不改写历史操作流水。
     */
    private void enrichCurrentReportState(List<MesTaskReportRecordEntity> records, String factory) {
        List<Long> reportIds = records.stream()
                .filter(item -> ReportRecordTypeEnum.REPORT.name().equals(item.getType()))
                .map(MesTaskReportRecordEntity::getReportId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (reportIds.isEmpty()) {
            return;
        }
        Map<Long, MesTaskReportEntity> reports = reportService.list(
                        new LambdaQueryWrapper<MesTaskReportEntity>()
                                .in(MesTaskReportEntity::getId, reportIds)
                                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                                .eq(MesTaskReportEntity::getFactoryCode, factory))
                .stream().collect(Collectors.toMap(MesTaskReportEntity::getId, Function.identity()));
        records.stream()
                .filter(item -> ReportRecordTypeEnum.REPORT.name().equals(item.getType()))
                .forEach(item -> {
                    MesTaskReportEntity report = reports.get(item.getReportId());
                    if (report == null) {
                        return;
                    }
                    item.setStatus(report.getStatus())
                            .setTimingAcceptedAt(report.getTimingAcceptedAt())
                            .setTimingEvidenceJson(report.getTimingEvidenceJson())
                            .setIdentifierBindingId(report.getIdentifierBindingId() == null ? null : report.getIdentifierBindingId().toString())
                            .setIdentifierNodeAction(report.getIdentifierNodeAction())
                            .setIdentifierBindingVersion(report.getIdentifierBindingVersion())
                            .setEffectiveReportNum(report.getEffectiveReportNum())
                            .setCorrectedReportNum(report.getCorrectedReportNum())
                            .setLastCorrectionTime(report.getLastCorrectionTime())
                            .setCorrectionPermitId(report.getCorrectionPermitId())
                            .setCorrectionPermitNo(report.getCorrectionPermitNo())
                            .setReverseReason(report.getReverseReason())
                            .setReverseTime(report.getReverseTime())
                            .setReverseWorkerCode(report.getReverseWorkerCode())
                            .setReverseWorkerName(report.getReverseWorkerName());
                });
    }

}
