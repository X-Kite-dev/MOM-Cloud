package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesReportMaterialConsumeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWarehouseInoutEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.service.MesReportMaterialConsumeService;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.MesTaskReportUnqualifiedService;
import com.kitex.mom.mes.service.MesReportCommandService;
import com.kitex.mom.mes.dto.MesReportOperatorContextDTO;
import com.kitex.mom.mes.dto.MesReportRequestCloseVO;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.service.MesWarehouseInoutService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-06
 */
@Tag(description = "mesTaskReport", name = "生产报工-操作明细")
@RestController
@RequestMapping("/mesTask/report")
@RequiredArgsConstructor
public class MesTaskReportController {

    private final MesTaskReportService service;
    private final MesReportMaterialConsumeService materialConsumeService;
    private final MesWarehouseInoutService warehouseInoutService;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesReportCommandService reportCommands;
    private final MesTaskReportUnqualifiedService unqualifiedReports;

    @Operation(summary = "查询本人报工原号回执", description = "只读查询首次成功结果及当前有效状态，不触发报工重试")
    @GetMapping("/receipt")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:show','mesTaskReport:report','mesStationPass:report','mesTaskReport:integration')")
    public R<MesReportCommandReceiptDTO> receipt(@RequestParam String requestId) {
        return R.ok(reportCommands.receipt(requestId));
    }

    @GetMapping("/command-context")
    @Operation(summary = "查询原号登记真实身份", description = "不包含令牌，不授予报工或离线许可")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:show','mesTaskReport:report','mesStationPass:report','mesTaskReport:integration')")
    public R<MesReportOperatorContextDTO> commandContext() {
        return R.ok(reportCommands.operatorContext());
    }

    @PostMapping("/close-unaccepted")
    @Operation(summary = "工站封存未受理原号", description = "已有成功返回原回执；未成功才永久封存，不冲销生产事实")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:report')")
    public R<MesReportCommandReceiptDTO> closeUnaccepted(@RequestBody MesReportRequestCloseVO input) {
        return R.ok(reportCommands.closeUnaccepted(input.getCommand(), MesReportChannelEnum.STATION, input.getReason()));
    }

    @Operation(summary = "报工记录分页查询", description = "查询原始报工事实及冲销状态")
    @GetMapping("/page")
    public R<IPage<MesTaskReportEntity>> page(Page<MesTaskReportEntity> pageParam, MesTaskReportEntity query) {
        QueryWrapper<MesTaskReportEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().eq(MesTaskReportEntity::getFactoryCode, factoryAccess.require("read"))
                .orderByDesc(MesTaskReportEntity::getReportTime);
        return R.ok(service.page(pageParam, wrapper));
    }

    @Operation(summary = "查询支持的报工模式", description = "工艺路线、PAD 和后端执行器共用的报工模式契约")
    @GetMapping("/modes")
    public R<List<ReportModeOptionDTO>> modes() {
        return R.ok(Arrays.stream(ReportModeEnum.values())
                .map(ReportModeOptionDTO::from)
                .collect(Collectors.toList()));
    }

    @Operation(summary = "生产报工", description = "生产报工")
    @PostMapping("/report")
//    @PreAuthorize("@pms.hasPermission('mesTaskReport:report')")
    public R<Void> report(@RequestBody MesTaskReportVO params) {
        service.report(params);
        return R.ok();
    }

    @Operation(summary = "系统集成报工",
            description = "使用独立权限并按执行模板的API触发来源校验；必须提供派工指定的operatorCode和operatorName")
    @PostMapping("/report-integration")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:integration')")
    public R<Void> reportIntegration(@RequestBody MesTaskReportVO params) {
        service.reportIntegration(params);
        return R.ok();
    }

    @Operation(summary = "旧报工冲销入口（已停用）", description = "冲销已升级为申请、异岗复核和事实重采流程")
    @PostMapping("/reverse")
    public R<Void> reverse(@RequestBody MesTaskReportReverseVO command) {
        return R.failed("直接冲销已停用，请使用报工受控冲销申请流程");
    }

    @Operation(summary = "查询报工实际用料", description = "按报工查询需求物料、实际物料、替代比例及实际批次")
    @GetMapping("/material-consume")
    public R<List<MesReportMaterialTraceVO>> materialConsume(@RequestParam Long reportId) {
        MesTaskReportEntity report = service.getById(reportId);
        if (report == null) {
            return R.failed("未找到报工记录");
        }
        List<MesReportMaterialTraceVO> result = new ArrayList<>();
        materialConsumeService.list(new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
                        .eq(MesReportMaterialConsumeEntity::getReportId, reportId)
                        .orderByAsc(MesReportMaterialConsumeEntity::getCreateTime))
                .forEach(item -> result.add(new MesReportMaterialTraceVO()
                        .setSourceType("人工投料")
                        .setRequirementMatCode(item.getRequirementMatCode())
                        .setMatCode(item.getMatCode())
                        .setMatBatch(item.getMatBatch())
                        .setMatBarcode(item.getMatBarcode())
                        .setRequirementNum(item.getRequirementNum())
                        .setConsumeNum(item.getConsumeNum())
                        .setSubstituteFlag(item.getSubstituteFlag())
                        .setSubstituteRatio(item.getSubstituteRatio())
                        .setStatus(item.getStatus())));
        warehouseInoutService.list(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                        .eq(MesWarehouseInoutEntity::getBillId, reportId)
                        .eq(MesWarehouseInoutEntity::getResourceNo, report.getRequestId())
                        .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode())
                        .orderByAsc(MesWarehouseInoutEntity::getCreateTime))
                .forEach(item -> {
                    BigDecimal ratio = item.getSubstituteRatio() == null ? BigDecimal.ONE : item.getSubstituteRatio();
                    BigDecimal requirementNum = ratio.compareTo(BigDecimal.ZERO) > 0
                            ? item.getOperateNum().divide(ratio, 12, RoundingMode.HALF_UP) : null;
                    result.add(new MesReportMaterialTraceVO()
                            .setSourceType("倒冲")
                            .setRequirementMatCode(item.getRequirementMatCode())
                            .setMatCode(item.getMatCode())
                            .setMatBatch(item.getMatBatch())
                            .setMatBarcode(item.getMatBarcode())
                            .setRequirementNum(requirementNum)
                            .setConsumeNum(item.getOperateNum())
                            .setSubstituteFlag(item.getSubstituteFlag())
                            .setSubstituteRatio(ratio)
                            .setStatus(report.getStatus()));
                });
        return R.ok(result);
    }

    @Operation(summary = "生产报工-不合格品上报", description = "生产报工-不合格品上报")
    @PostMapping("/unqualified")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:unqualified')")
    public R<MesProductionNgSourceDTO> unqualified(@RequestBody MesTaskReportUnqualifiedVO params) {
        service.unqualified(params);
        return R.ok(unqualifiedReports.source(params.getRequestId()));
    }

    @Operation(summary = "查询不良报工原计量", description = "仅查询当前工厂原请求结果；未库存化不是可用库存，历史缺单位不补造")
    @GetMapping("/unqualified/source")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:show','mesTaskReport:unqualified')")
    public R<MesProductionNgSourceDTO> unqualifiedSource(@RequestParam String requestId) {
        return R.ok(unqualifiedReports.source(requestId));
    }

    @Operation(summary = "不良报工时间证据分页", description = "独立NG报告域，只读原事实，不重算历史、不触发报工")
    @GetMapping("/unqualified/timing-page")
    @PreAuthorize("@pms.hasPermission('mesTaskReport:show','mesTaskReport:unqualified')")
    public R<IPage<MesUnqualifiedTimingRecordDTO>> unqualifiedTimingPage(
            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String taskCode, @RequestParam(required = false) String arrangeCode,
            @RequestParam(required = false) String identifier) {
        return R.ok(unqualifiedReports.timingPage(current, size, taskCode, arrangeCode, identifier));
    }

    @Operation(summary = "生产投料", description = "生产投料")
    @PostMapping("/feed")
//    @PreAuthorize("@pms.hasPermission('mesTaskReport:feed')")
    public R<Void> feed(@RequestBody MesTaskReportFeedVO params) {
        service.feed(params);
        return R.ok();
    }


}
