package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesMaterialListDetailDTO;
import com.kitex.mom.mes.service.MesMaterialListDetailService;
import com.kitex.mom.mes.vo.MesMaterialListPickVO;
import com.kitex.mom.mes.vo.MesMaterialListReturnVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>
 * 用料清单明细表 前端控制器
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/materialListDetail")
@Tag(description = "materialListDetail", name = "用料清单明细管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesMaterialListDetailController {

    private final MesMaterialListDetailService materialListDetailService;

    /**
     * 根据用料清单ID获取明细列表
     *
     * @param materialListId 用料清单ID
     * @return 明细列表
     */
    @Operation(summary = "根据用料清单ID获取明细列表")
    @GetMapping("/list/{materialListId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<List<MesMaterialListDetailDTO>> getDetailsByMaterialListId(@PathVariable Long materialListId) {
        return R.ok(materialListDetailService.getDetailsByMaterialListId(materialListId));
    }

    /**
     * 根据工单ID获取明细列表
     *
     * @param taskId 工单ID
     * @return 明细列表
     */
    @Operation(summary = "根据工单ID获取明细列表")
    @GetMapping("/task/{taskId}")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<List<MesMaterialListDetailDTO>> getDetailsByTaskId(@PathVariable Long taskId) {
        return R.ok(materialListDetailService.getDetailsByTaskId(taskId));
    }

    /**
     * 物料领料操作
     *
     * @param pickVO 领料信息
     * @return 领料结果
     */
    @Operation(summary = "物料领料操作")
    @PostMapping("/pick")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("物料领料操作")
    public R<Boolean> pickMaterial(@RequestBody @Validated MesMaterialListPickVO pickVO) {
        return R.ok(materialListDetailService.pickMaterial(pickVO));
    }

    /**
     * 物料退料操作
     *
     * @param returnVO 退料信息
     * @return 退料结果
     */
    @Operation(summary = "物料退料操作")
    @PostMapping("/return")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("物料退料操作")
    public R<Boolean> returnMaterial(@RequestBody @Validated MesMaterialListReturnVO returnVO) {
        return R.ok(materialListDetailService.returnMaterial(returnVO));
    }

    /**
     * 批量领料操作
     *
     * @param pickVOList 批量领料信息
     * @return 领料结果
     */
    @Operation(summary = "批量领料操作")
    @PostMapping("/batchPick")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("批量领料操作")
    public R<Boolean> batchPickMaterial(@RequestBody @Validated List<MesMaterialListPickVO> pickVOList) {
        return R.ok(materialListDetailService.batchPickMaterial(pickVOList));
    }

    /**
     * 更新明细的领料状态
     *
     * @param id         明细ID
     * @param pickStatus 领料状态
     * @return 更新结果
     */
    @Operation(summary = "更新明细的领料状态")
    @PutMapping("/{id}/pickStatus/{pickStatus}")
    @PreAuthorize("@pms.hasPermission('mesTask:update')")
    @SysLog("更新明细的领料状态")
    public R<Boolean> updatePickStatus(@PathVariable Long id, @PathVariable String pickStatus) {
        return R.ok(materialListDetailService.updatePickStatus(id, pickStatus));
    }

    /**
     * 检查物料库存是否充足
     *
     * @param materialCode     物料编码
     * @param requiredQuantity 需求数量
     * @return 是否充足
     */
    @Operation(summary = "检查物料库存是否充足")
    @GetMapping("/checkStock/{materialCode}")
    @PreAuthorize("@pms.hasPermission('mesTask:show')")
    public R<Boolean> checkMaterialStock(@PathVariable String materialCode,
                                         @RequestParam BigDecimal requiredQuantity) {
        return R.ok(materialListDetailService.checkMaterialStock(materialCode, requiredQuantity));
    }
}
