package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.dto.MesOfflineReportReplayDTO;
import com.kitex.mom.mes.entity.MesStationPassRecordEntity;
import com.kitex.mom.mes.entity.MesOfflineReportReplayEntity;
import com.kitex.mom.mes.mapper.MesStationPassRecordMapper;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.MesReportCommandService;
import com.kitex.mom.mes.dto.MesReportCommandReceiptDTO;
import com.kitex.mom.mes.dto.MesReportRequestCloseVO;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.service.MesOfflineReportPermitService;
import com.kitex.mom.mes.service.MesOfflineReportReplayService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.service.impl.MesStationPassQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "扫码过站与防错", description = "通用离散制造扫码过站、候选工序和防错履历")
@RestController
@RequestMapping("/mesTask/checkReport")
@RequiredArgsConstructor
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesStationPassController {
    private final MesStationPassQueryService queryService;
    private final MesTaskReportService reportService;
    private final MesReportCommandService reportCommands;
    private final MesOfflineReportPermitService offlineReportPermitService;
    private final MesOfflineReportReplayService offlineReportReplayService;
    private final MesStationPassRecordMapper passRecordMapper;
    private final MesActualFactoryAccessService factoryAccess;

    @Operation(summary = "APP扫码查询可执行工序", description = "兼容现有APP地址；候选由当前用户、流转卡归属和前序事实计算")
    @GetMapping("/detailListApp")
    @PreAuthorize("@pms.hasPermission('mesStationPass:report')")
    public R<Map<String, Object>> detailListApp(@RequestParam("transferCode") String transferCode) {
        return R.ok(queryService.preview(transferCode));
    }

    @Operation(summary = "APP执行过站报工", description = "兼容现有APP地址；实际进入统一报工、防错、库存和质量事务")
    @PostMapping("/reportManage")
    @PreAuthorize("@pms.hasPermission('mesStationPass:report')")
    public R<Void> reportManage(@RequestBody MesTaskReportVO command) {
        reportService.reportMobile(command);
        return R.ok();
    }

    @PostMapping("/close-unaccepted")
    @Operation(summary = "移动端封存未受理原号", description = "不能删除未知原号或把成功报工当作未执行")
    @PreAuthorize("@pms.hasPermission('mesStationPass:report')")
    public R<MesReportCommandReceiptDTO> closeUnaccepted(@RequestBody MesReportRequestCloseVO input) {
        return R.ok(reportCommands.closeUnaccepted(input.getCommand(), MesReportChannelEnum.MOBILE, input.getReason()));
    }

    @Operation(summary = "批量重放PDA弱网报工",
            description = "逐条校验短时授权并进入在线报工同一事务；单条业务拒绝不会阻断同批其他命令")
    @PostMapping("/offline/replay")
    @PreAuthorize("@pms.hasPermission('mesStationPass:report')")
    public R<MesOfflineReportReplayDTO.BatchResult> replayOffline(
            @RequestBody MesOfflineReportReplayDTO.BatchRequest request) {
        return R.ok(offlineReportPermitService.replay(request));
    }

    @Operation(summary = "弱网报工重放审计分页查询",
            description = "管理端核对PDA弱网命令的采集、接收、尝试、拒绝原因和最终报工事实")
    @GetMapping("/offline/replay/page")
    @PreAuthorize("@pms.hasPermission('mesStationPass:view')")
    public R<IPage<MesOfflineReportReplayEntity>> replayPage(Page<MesOfflineReportReplayEntity> page,
                                                             MesOfflineReportReplayEntity query) {
        QueryWrapper<MesOfflineReportReplayEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().eq(MesOfflineReportReplayEntity::getFactoryCode, factoryAccess.require("read"))
                .orderByDesc(MesOfflineReportReplayEntity::getReceivedAt)
                .orderByDesc(MesOfflineReportReplayEntity::getId);
        return R.ok(offlineReportReplayService.page(page, wrapper));
    }

    @Operation(summary = "查询标识过站防错履历")
    @GetMapping("/history")
    @PreAuthorize("@pms.hasPermission('mesStationPass:view','mesStationPass:report')")
    public R<List<MesStationPassRecordEntity>> history(@RequestParam("transferCode") String transferCode) {
        String factory = factoryAccess.require("read");
        List<String> resolvedCodes = queryService.historyTransferCodes(transferCode);
        return R.ok(passRecordMapper.selectList(new LambdaQueryWrapper<MesStationPassRecordEntity>()
                .eq(MesStationPassRecordEntity::getFactoryCode, factory)
                .in(MesStationPassRecordEntity::getTransferCode, resolvedCodes)
                .orderByAsc(MesStationPassRecordEntity::getCheckedTime)
                .orderByAsc(MesStationPassRecordEntity::getId)));
    }

    @Operation(summary = "过站防错履历分页查询", description = "查询所有报工模式的放行与阻断判定，阻断事实不会随报工事务回滚")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesStationPass:view')")
    public R<IPage<MesStationPassRecordEntity>> page(Page<MesStationPassRecordEntity> page,
                                                     MesStationPassRecordEntity query) {
        QueryWrapper<MesStationPassRecordEntity> wrapper = QueryGenerator.initQueryWrapper(query);
        wrapper.lambda().eq(MesStationPassRecordEntity::getFactoryCode, factoryAccess.require("read"))
                .orderByDesc(MesStationPassRecordEntity::getCheckedTime)
                .orderByDesc(MesStationPassRecordEntity::getId);
        return R.ok(passRecordMapper.selectPage(page, wrapper));
    }
}
