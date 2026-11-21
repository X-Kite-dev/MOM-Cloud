package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesMaterialListRecordDTO;
import com.kitex.mom.mes.service.MesMaterialListRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用料清单领退料记录表 前端控制器
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/materialListRecord")
@PreAuthorize("@pms.hasPermission('mesTask:show')")
@Tag(description = "materialListRecord", name = "用料清单领退料记录管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesMaterialListRecordController {

    private final MesMaterialListRecordService materialListRecordService;

    /**
     * 根据用料清单明细ID获取领退料记录
     *
     * @param materialListDetailId 用料清单明细ID
     * @return 记录列表
     */
    @Operation(summary = "根据用料清单明细ID获取领退料记录")
    @GetMapping("/detail/{materialListDetailId}")
    public R<List<MesMaterialListRecordDTO>> getRecordsByDetailId(@PathVariable Long materialListDetailId) {
        return R.ok(materialListRecordService.getRecordsByDetailId(materialListDetailId));
    }

    /**
     * 根据工单ID获取领退料记录
     *
     * @param taskId 工单ID
     * @return 记录列表
     */
    @Operation(summary = "根据工单ID获取领退料记录")
    @GetMapping("/task/{taskId}")
    public R<List<MesMaterialListRecordDTO>> getRecordsByTaskId(@PathVariable Long taskId) {
        return R.ok(materialListRecordService.getRecordsByTaskId(taskId));
    }

    /**
     * 获取工单的领退料汇总信息
     *
     * @param taskId 工单ID
     * @return 汇总信息
     */
    @Operation(summary = "获取工单的领退料汇总信息")
    @GetMapping("/summary/{taskId}")
    public R<List<Map<String, Object>>> getPickReturnSummaryByTaskId(@PathVariable Long taskId) {
        return R.ok(materialListRecordService.getPickReturnSummaryByTaskId(taskId));
    }
}
