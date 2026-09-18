package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesTaskReportFeedVO;
import com.kitex.mom.mes.dto.MesTaskReportReverseVO;
import com.kitex.mom.mes.dto.MesTaskReportUnqualifiedVO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.ReportModeOptionDTO;
import com.kitex.mom.mes.dto.MesReportMaterialTraceVO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesReportMaterialConsumeEntity;
import com.kitex.mom.mes.entity.MesWarehouseInoutEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.service.MesTaskReportReverseService;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.MesReportMaterialConsumeService;
import com.kitex.mom.mes.service.MesWarehouseInoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;

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
    private final MesTaskReportReverseService reverseService;
    private final MesReportMaterialConsumeService materialConsumeService;
    private final MesWarehouseInoutService warehouseInoutService;

    @Operation(summary = "报工记录分页查询", description = "查询原始报工事实及冲销状态")
    @GetMapping("/page")
    public R<IPage<MesTaskReportEntity>> page(Page<MesTaskReportEntity> pageParam, MesTaskReportEntity query) {
        QueryWrapper<MesTaskReportEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().orderByDesc(MesTaskReportEntity::getReportTime);
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

    @Operation(summary = "反报工/冲销", description = "反向恢复库存、投料消耗和生产数量，原记录永久保留")
    @PostMapping("/reverse")
    public R<Void> reverse(@RequestBody MesTaskReportReverseVO command) {
        reverseService.reverse(command);
        return R.ok();
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
//    @PreAuthorize("@pms.hasPermission('mesTaskReport:unqualified')")
    public R<Void> unqualified(@RequestBody MesTaskReportUnqualifiedVO params) {
        service.unqualified(params);
        return R.ok();
    }

    @Operation(summary = "生产投料", description = "生产投料")
    @PostMapping("/feed")
//    @PreAuthorize("@pms.hasPermission('mesTaskReport:feed')")
    public R<Void> feed(@RequestBody MesTaskReportFeedVO params) {
        service.feed(params);
        return R.ok();
    }


}
