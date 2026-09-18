package com.kitex.mom.qms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.dto.inspection.InspectionAppendParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultBatchParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultParam;
import com.kitex.mom.qms.dto.inspection.InspectionProductCreateParam;
import com.kitex.mom.qms.dto.req.InspectionProductTaskReq;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;
import com.kitex.mom.qms.dto.resp.QmsInspectionProductResultDTO;
import com.kitex.mom.qms.dto.resp.QmsInspectionProductTaskAppVO;
import com.kitex.mom.qms.dto.resp.QmsInspectionProductTaskDTO;
import com.kitex.mom.qms.entity.QmsInspectionProductHandleRecordEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductHistoryEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.service.InspectionProductHandleRecordService;
import com.kitex.mom.qms.service.InspectionProductHistoryService;
import com.kitex.mom.qms.service.InspectionProductTaskService;
import com.kitex.mom.qms.service.QmsReworkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 来料质检任务
 *
 * @author kitex
 * @date 2022/12/2
 */
@RestController
@RequestMapping("/inspection/product/task")
@RequiredArgsConstructor
@Tag(description = "productTask", name = "4.来料质检任务")
public class InspectionProductTaskController {

    private final InspectionProductTaskService service;

    private final InspectionProductHistoryService historyService;

    private final InspectionProductHandleRecordService handleRecordService;
    private final QmsReworkOrderService reworkOrderService;

    @Operation(summary = "判断是否已经有同类型任务", description = "判断是否已经有同类型任务")
    @GetMapping("/existTask")
    public R<Boolean> existTask(String matCode, String procedureCode, String batchNo) {
        return R.ok(service.existTask(matCode, procedureCode, batchNo));
    }


    @Operation(summary = "创建质检任务", description = "创建质检任务")
    @PostMapping("/create")
    @PreAuthorize("@pms.hasPermission('productTask:add')")
    public R<Void> create(@RequestBody InspectionProductCreateParam createParam) {
        service.create(createParam);
        return R.ok();
    }

    @Operation(summary = "修改质检任务", description = "修改质检任务")
    @PutMapping("/update")
    public R<Void> update(@RequestBody InspectionProductCreateParam updateParam) {
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
    @PreAuthorize("@pms.hasPermission('productTask:show')")
    public R<Page<QmsInspectionProductTaskEntity>> page(Page<QmsInspectionProductTaskEntity> page,
                                                        InspectionProductTaskReq param, CommonQuery commonQuery) {
        return R.ok(service.page(page, param, commonQuery));
    }

    @Operation(summary = "质检任务分页", description = "质检任务分页")
    @GetMapping("/app/page")
    @PreAuthorize("@pms.hasPermission('productTask:show')")
    public R<QmsInspectionProductTaskAppVO> appPage(Page<QmsInspectionProductTaskEntity> page,
                                                    InspectionProductTaskReq param, CommonQuery commonQuery) {
        return R.ok(service.queryAppPage(page, param, commonQuery));
    }

    @Operation(summary = "查询质检任务状态的数量", description = "查询各质检任务状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject InspectionProductTaskReq param) {
        return R.ok(service.getStatusCount(param));
    }

    @Operation(summary = "获取质检任务详情", description = "获取质检任务详情")
    @GetMapping("/findById")
    public R<QmsInspectionProductTaskDTO> findById(Long id) {
        return R.ok(service.findById(id));
    }

    @Operation(summary = "获取质检任务详情", description = "获取质检任务详情")
    @GetMapping("/getById")
    public R<QmsInspectionProductResultDTO> getById(Long id) {
        return R.ok(service.queryById(id));
    }

    @Operation(summary = "根据任务Id获取质检项列表", description = "根据任务Id获取质检项列表")
    @GetMapping("/findDetailsByTaskId")
    public R<List<QmsInspectionProductTaskDetailEntity>> findDetailsByTaskId(Long taskId) {
        return R.ok(service.findDetailsByTaskId(taskId));
    }

    @Operation(summary = "获取质检项详情", description = "获取质检项详情")
    @GetMapping("/findDetailById")
    public R<QmsInspectionProductTaskDetailEntity> findDetailById(Long id) {
        return R.ok(service.findDetailById(id));
    }

    @Operation(summary = "作废质检任务", description = "作废质检任务")
    @PostMapping("/canceled")
    public R<Void> canceled(Long taskId, String reason) {
        service.canceled(taskId, reason);
        return R.ok();
    }

    @Operation(summary = "处置", description = "作废质检任务")
    @PostMapping("/handleFinish")
    public R<Void> handleFinish(@RequestBody HandleFinishDTO dto) {
        service.handleFinish(dto);
        return R.ok();
    }

    @Operation(summary = "返工返修", description = "返工返修")
    @PostMapping("/handleRework")
    public R<com.kitex.mom.qms.entity.QmsReworkOrderEntity> handleRework(Long id) {
        return R.ok(reworkOrderService.create("PRODUCT", id));
    }

    @Operation(summary = "免检质检任务", description = "免检质检任务")
    @PostMapping("/exemption")
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
    @PreAuthorize("@pms.hasPermission('productTask:submit')")
    public R<Void> submitInspectionResult(Long taskId) {
        service.submitInspectionResult(taskId);
        return R.ok();
    }

    @Operation(summary = "根据任务Id获取质检项报表", description = "根据任务Id获取质检项报表")
    @GetMapping("/findReportListByTaskId")
    @PreAuthorize("@pms.hasPermission('productTask:report')")
    public R<ReportListRespDTO> findReportListByTaskId(@RequestParam("taskId") Long taskId) {
        return R.ok(service.findReportListByTaskId(taskId));
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
    @PreAuthorize("@pms.hasPermission('productTask:history')")
    public R<List<QmsInspectionProductHistoryEntity>> findAllByTaskId(Long taskId) {
        return R.ok(historyService.findAllByTaskId(taskId));
    }

    @Operation(summary = "质检任务详情历史信息", description = "质检任务详情历史信息")
    @GetMapping("/history/findTaskDetailHistoryById")
    public R<QmsInspectionProductHistoryEntity> findTaskHistoryById(Long id) {
        return R.ok(historyService.findById(id));
    }

    @Operation(summary = "处置明细", description = "处置明细")
    @GetMapping("/handleRecord/queryByTaskId")
    public R<List<QmsInspectionProductHandleRecordEntity>> queryByTaskId(Long taskId) {
        return R.ok(handleRecordService.list(new LambdaQueryWrapper<QmsInspectionProductHandleRecordEntity>()
                .eq(QmsInspectionProductHandleRecordEntity::getTaskId, taskId)
                .orderByDesc(QmsInspectionProductHandleRecordEntity::getHandleTime)));
    }
}
