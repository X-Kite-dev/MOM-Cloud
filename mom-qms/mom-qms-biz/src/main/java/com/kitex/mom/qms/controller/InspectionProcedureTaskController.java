package com.kitex.mom.qms.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.qms.dto.QmsProcedureSourceWithdrawalDTO;
import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.dto.inspection.InspectionAppendParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultBatchParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultParam;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureCreateParam;
import com.kitex.mom.qms.dto.req.InspectionProcedureTaskReq;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;
import com.kitex.mom.qms.dto.resp.QmsInspectionProcedureResultDTO;
import com.kitex.mom.qms.dto.resp.QmsInspectionProcedureTaskAppVO;
import com.kitex.mom.qms.dto.resp.QmsInspectionProcedureTaskDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureHandleRecordEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureHistoryEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.service.InspectionProcedureHandleRecordService;
import com.kitex.mom.qms.service.InspectionProcedureHistoryService;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.QmsReworkOrderService;
import com.kitex.mom.qms.service.impl.QmsInspectionEventService;
import com.kitex.mom.qms.entity.QmsInspectionEventEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.kitex.mom.qms.dto.QmsFirstInspectionDecisionDTO;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 来料质检任务
 *
 * @author kitex
 * @date 2022/12/2
 */
@RestController
@RequestMapping("/inspection/procedure/task")
@RequiredArgsConstructor
@Tag(description = "procedureTask", name = "4.来料质检任务")
public class InspectionProcedureTaskController {

    private final InspectionProcedureTaskService service;

    private final InspectionProcedureHistoryService historyService;

    private final InspectionProcedureHandleRecordService handleRecordService;
    private final QmsReworkOrderService reworkOrderService;
    private final QmsInspectionEventService inspectionEvents;

    @Operation(summary = "判断是否已经有同类型任务", description = "判断是否已经有同类型任务")
    @GetMapping("/existTask")
    public R<Boolean> existTask(String matCode, String procedureCode, String batchNo) {
        return R.ok(service.existTask(matCode, procedureCode, batchNo));
    }

    @Operation(summary = "根据物料/供应商/批次号 查询质检任务, 创建质检时使用", description = "根据物料/供应商/批次号 查询质检任务, 创建质检时使用")
    @GetMapping("/getTaskByMatCodeAndSupplierCodeAndBatchNo")
    public R<QmsInspectionProcedureTaskEntity> getTaskByMatCodeAndSupplierCodeAndBatchNo(String matCode,
                                                                                         String processCode, String batchNo) {
        return R.ok(service.getTaskByMatCodeAndSupplierCodeAndBatchNo(matCode, processCode, batchNo));
    }

    @Operation(summary = "查询质检任务", description = "查询质检任务")
    @GetMapping("/getTaskByIds")
    public R<List<QmsInspectionProcedureTaskEntity>> getTaskByIds(@RequestParam("ids") List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return R.ok(new ArrayList<>());
        }
        return R.ok(service.list(new LambdaQueryWrapper<QmsInspectionProcedureTaskEntity>()
                .in(QmsInspectionProcedureTaskEntity::getId, ids)));
    }

    @Operation(summary = "创建质检任务", description = "创建质检任务")
    @PostMapping("/create")
//    @PreAuthorize("@pms.hasPermission('procedureTask:add')")
    public R<Long> create(@RequestBody InspectionProcedureCreateParam createParam) {
        return R.ok(service.create(createParam));
    }

    @Operation(summary = "创建质检任务", description = "创建质检任务")
    @PostMapping("/batchCreate")
    public R<Map<String, Long>> batchCreate(@RequestBody List<InspectionProcedureCreateParam> createParam) {
        return R.ok(service.batchCreate(createParam));
    }

    @Operation(summary = "修改质检任务", description = "修改质检任务")
    @PutMapping("/update")
    public R<Void> update(@RequestBody InspectionProcedureCreateParam updateParam) {
        service.update(updateParam);
        return R.ok();
    }

    @Operation(summary = "追加质检任务", description = "追加质检任务")
    @PostMapping("/append")
    public R<Void> append(@RequestBody InspectionAppendParam appendParam) {
        service.append(appendParam);
        return R.ok();
    }

    @SuppressWarnings("rawtypes")
    @Operation(summary = "质检任务分页", description = "质检任务分页")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<Page<QmsInspectionProcedureTaskEntity>> page(Page<QmsInspectionProcedureTaskEntity> page,
                                                          InspectionProcedureTaskReq param, CommonQuery commonQuery) {
        return R.ok(service.page(page, param, commonQuery));
    }

    @Operation(summary = "质检任务分页", description = "质检任务分页")
    @GetMapping("/app/page")
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<QmsInspectionProcedureTaskAppVO> appPage(Page<QmsInspectionProcedureTaskEntity> page,
                                                      InspectionProcedureTaskReq param, CommonQuery commonQuery) {
        return R.ok(service.queryAppPage(page, param, commonQuery));
    }

    @Operation(summary = "查询质检任务状态的数量", description = "查询各质检任务状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject InspectionProcedureTaskReq param) {
        return R.ok(service.getStatusCount(param));
    }

    @Operation(summary = "获取质检任务详情", description = "获取质检任务详情")
    @GetMapping("/findById")
    public R<QmsInspectionProcedureTaskDTO> findById(Long id) {
        return R.ok(service.findById(id));
    }

    @Operation(summary = "获取质检任务详情", description = "获取质检任务详情")
    @GetMapping("/getById")
    public R<QmsInspectionProcedureResultDTO> getById(Long id) {
        return R.ok(service.queryById(id));
    }

    @Operation(summary = "根据任务Id获取质检项列表", description = "根据任务Id获取质检项列表")
    @GetMapping("/findDetailsByTaskId")
    public R<List<QmsInspectionProcedureTaskDetailEntity>> findDetailsByTaskId(Long taskId) {
        return R.ok(service.findDetailsByTaskId(taskId));
    }

    @Operation(summary = "获取质检项详情", description = "获取质检项详情")
    @GetMapping("/findDetailById")
    public R<QmsInspectionProcedureTaskDetailEntity> findDetailById(Long id) {
        return R.ok(service.findDetailById(id));
    }

    @Operation(summary = "作废质检任务", description = "作废质检任务")
    @PostMapping("/canceled")
    @PreAuthorize("@pms.hasPermission('procedureTask:cancel')")
    public R<Void> canceled(Long taskId, String reason) {
        service.canceled(taskId, reason);
        return R.ok();
    }

    @Operation(summary = "MES来源计划级联作废未开始的工序质检任务")
    @Inner
    @PostMapping("/internal/source-withdrawal")
    public R<QmsProcedureSourceWithdrawalDTO.Receipt> withdrawByMesSource(
            @RequestBody QmsProcedureSourceWithdrawalDTO.Command command) {
        return R.ok(service.withdrawByMesSource(command));
    }

    @Operation(summary = "免检质检任务", description = "免检质检任务")
    @PostMapping("/exemption")
    @PreAuthorize("@pms.hasPermission('procedureTask:exemption')")
    public R<Void> exemption(Long taskId) {
        service.exemption(taskId);
        return R.ok();
    }

    @Operation(summary = "暂存质检项结果", description = "暂存质检项结果")
    @PostMapping("/temporarilySave")
    public R<Void> temporarilySave(@RequestBody InspectionCheckResultParam param) {
        service.temporarilySave(param);
        return R.ok();
    }

    @Operation(summary = "暂存质检项结果", description = "暂存质检项结果")
    @PostMapping("/batchTemporarilySave")
    public R<Void> batchTemporarilySave(@RequestBody InspectionCheckResultBatchParam param) {
        service.batchTemporarilySave(param);
        return R.ok();
    }

    @Operation(summary = "提交质检项结果", description = "提交质检项结果")
    @PostMapping("/submitInspectionResult")
    @PreAuthorize("@pms.hasPermission('procedureTask:submit')")
    public R<Void> submitInspectionResult(Long taskId) {
        service.submitInspectionResult(taskId);
        return R.ok();
    }

    @Operation(summary = "根据任务Id获取质检项报表", description = "根据任务Id获取质检项报表")
    @GetMapping("/findReportListByTaskId")
    @PreAuthorize("@pms.hasPermission('procedureTask:report')")
    public R<ReportListRespDTO> findReportListByTaskId(@RequestParam("taskId") Long taskId) {
        return R.ok(service.findReportListByTaskId(taskId));
    }

    @Operation(summary = "处置", description = "作废质检任务")
    @PostMapping("/handleFinish")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle')")
    public R<Void> handleFinish(@RequestBody HandleFinishDTO dto) {
        service.handleFinish(dto);
        return R.ok();
    }

    @Operation(summary = "返工返修", description = "返工返修")
    @PostMapping("/handleRework")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle')")
    public R<com.kitex.mom.qms.entity.QmsReworkOrderEntity> handleRework(Long id) {
        return R.ok(reworkOrderService.create("PROCEDURE", id));
    }

    /**
     * 导出报表
     *
     * @param taskId
     * @return
     */
    @Operation(summary = "导出", description = "导出")
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam("taskId") Long taskId, Integer num) {
        return service.download(taskId, num);
    }


    @Operation(summary = "质检任务历史列表", description = "质检任务历史列表")
    @GetMapping("/history/findAllByTaskId")
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<List<QmsInspectionProcedureHistoryEntity>> findAllByTaskId(Long taskId) {
        return R.ok(historyService.findAllByTaskId(taskId));
    }

    @Operation(summary = "质检任务详情历史信息", description = "质检任务详情历史信息")
    @GetMapping("/history/findTaskDetailHistoryById")
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<QmsInspectionProcedureHistoryEntity> findTaskHistoryById(Long id) {
        return R.ok(historyService.findById(id));
    }


    @GetMapping("/history/events")
    @Operation(summary = "查询不可变检验事件原件")
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<List<QmsInspectionEventEntity>> events(@RequestParam Long taskId) {
        return R.ok(inspectionEvents.events("PROCEDURE", taskId));
    }

    @GetMapping("/history/firstDecision")
    @Operation(summary = "查询首次检验判定依据")
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<QmsFirstInspectionDecisionDTO> firstDecision(@RequestParam Long taskId) {
        return R.ok(historyService.firstDecision(taskId));
    }

    @Operation(summary = "处置明细", description = "处置明细")
    @GetMapping("/handleRecord/queryByTaskId")
    public R<List<QmsInspectionProcedureHandleRecordEntity>> queryByTaskId(Long taskId) {
        return R.ok(handleRecordService.list(new LambdaQueryWrapper<QmsInspectionProcedureHandleRecordEntity>()
                .eq(QmsInspectionProcedureHandleRecordEntity::getTaskId, taskId)
                .orderByDesc(QmsInspectionProcedureHandleRecordEntity::getHandleTime)));
    }
}
