package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesMaterialListDTO;
import com.kitex.mom.mes.service.MesMaterialListService;
import com.kitex.mom.mes.vo.MesMaterialListQueryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 用料清单主表 前端控制器
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/materialList")
@Tag(description = "materialList", name = "用料清单管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesMaterialListController {

    private final MesMaterialListService materialListService;

    /**
     * 分页查询用料清单
     *
     * @param current 当前页
     * @param size    页大小
     * @param queryVO 查询条件
     * @return 分页结果
     */
    @Operation(summary = "分页查询用料清单")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<Page<MesMaterialListDTO>> getMaterialListPage(
            @RequestParam(value = "current", defaultValue = "1") Long current,
            @RequestParam(value = "size", defaultValue = "10") Long size,
            MesMaterialListQueryVO queryVO) {
        return R.ok(materialListService.getMaterialListPage(current, size, queryVO));
    }

    /**
     * 根据工单ID获取用料清单详情
     *
     * @param taskId 工单ID
     * @return 用料清单详情
     */
    @Operation(summary = "根据工单ID获取用料清单详情")
    @GetMapping("/task/{taskId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<MesMaterialListDTO> getMaterialListByTaskId(@PathVariable Long taskId) {
        return R.ok(materialListService.getMaterialListByTaskId(taskId));
    }

    /**
     * 根据工单创建用料清单
     *
     * @param taskId 工单ID
     * @return 创建结果
     */
    @Operation(summary = "根据工单创建用料清单")
    @PostMapping("/createByTask/{taskId}")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("根据工单创建用料清单")
    public R<Boolean> createMaterialListByTask(@PathVariable Long taskId) {
        return R.ok(materialListService.createMaterialListByTask(taskId));
    }

    /**
     * 更新用料清单状态
     *
     * @param id     用料清单ID
     * @param status 状态
     * @return 更新结果
     */
    @Operation(summary = "更新用料清单状态")
    @PutMapping("/{id}/status/{status}")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("更新用料清单状态")
    public R<Boolean> updateMaterialListStatus(@PathVariable Long id, @PathVariable String status) {
        return R.ok(materialListService.updateMaterialListStatus(id, status));
    }

    /**
     * 重新生成用料清单
     *
     * @param id 用料清单ID
     * @return 重新生成结果
     */
    @Operation(summary = "重新生成用料清单")
    @PostMapping("/{id}/regenerate")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("重新生成用料清单")
    public R<Boolean> regenerateMaterialList(@PathVariable Long id) {
        return R.ok(materialListService.regenerateMaterialList(id));
    }

    /**
     * 检查用料清单是否完成
     *
     * @param id 用料清单ID
     * @return 是否完成
     */
    @Operation(summary = "检查用料清单是否完成")
    @GetMapping("/{id}/checkCompleted")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<Boolean> checkMaterialListCompleted(@PathVariable Long id) {
        return R.ok(materialListService.checkMaterialListCompleted(id));
    }

    /**
     * 批量创建用料清单
     *
     * @param taskIds 工单ID列表
     * @return 创建结果
     */
    @Operation(summary = "批量创建用料清单")
    @PostMapping("/batchCreateByTasks")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("批量创建用料清单")
    public R<Boolean> batchCreateMaterialListByTasks(@RequestBody List<Long> taskIds) {
        return R.ok(materialListService.batchCreateMaterialListByTasks(taskIds));
    }
}
