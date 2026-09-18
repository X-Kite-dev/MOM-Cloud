package com.kitex.mom.wms.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.component.PermissionService;
import com.kitex.mom.wms.dto.WmsAllocationBillAddDTO;
import com.kitex.mom.wms.dto.WmsAllocationCancelDTO;
import com.kitex.mom.wms.dto.WmsAllocationBillQueryDTO;
import com.kitex.mom.wms.dto.WmsCrossWarehouseTransferDTO;
import com.kitex.mom.wms.dto.app.WmsAllocationBillAppDTO;
import com.kitex.mom.wms.entity.WmsAllocationBillEntity;
import com.kitex.mom.wms.service.WmsAllocationBillService;
import com.kitex.mom.wms.vo.WmsAllocationBillExcelVO;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 调拨单
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsAllocationBill")
@Tag(description = "wmsAllocationBill", name = "调拨单管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class WmsAllocationBillController {

    private final WmsAllocationBillService wmsAllocationBillService;

    private final PermissionService permissionService;

    /**
     * 分页查询
     *
     * @param page              分页对象
     * @param wmsAllocationBill 调拨单
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_view')")
    public R<Page<WmsAllocationBillEntity>> getWmsAllocationBillPage(@ParameterObject SearchPage page, @ParameterObject WmsAllocationBillQueryDTO wmsAllocationBill) {
        requireWarehouseScope(wmsAllocationBill);
        LambdaQueryWrapper<WmsAllocationBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getBillNo()), WmsAllocationBillEntity::getBillNo, wmsAllocationBill.getBillNo());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseCode()), WmsAllocationBillEntity::getOldWarehouseCode, wmsAllocationBill.getOldWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseName()), WmsAllocationBillEntity::getOldWarehouseName, wmsAllocationBill.getOldWarehouseName());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseCode()), WmsAllocationBillEntity::getNewWarehouseCode, wmsAllocationBill.getNewWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseName()), WmsAllocationBillEntity::getNewWarehouseName, wmsAllocationBill.getNewWarehouseName());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getAllocationState()), WmsAllocationBillEntity::getAllocationState, wmsAllocationBill.getAllocationState());
        wrapper.orderByDesc(WmsAllocationBillEntity::getCreateTime).orderByAsc(WmsAllocationBillEntity::getAllocationState);
        return R.ok(wmsAllocationBillService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject WmsAllocationBillQueryDTO wmsAllocationBill) {
        requireWarehouseScope(wmsAllocationBill);
        LambdaQueryWrapper<WmsAllocationBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getBillNo()), WmsAllocationBillEntity::getBillNo, wmsAllocationBill.getBillNo());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseCode()), WmsAllocationBillEntity::getOldWarehouseCode, wmsAllocationBill.getOldWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getOldWarehouseName()), WmsAllocationBillEntity::getOldWarehouseName, wmsAllocationBill.getOldWarehouseName());
        wrapper.eq(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseCode()), WmsAllocationBillEntity::getNewWarehouseCode, wmsAllocationBill.getNewWarehouseCode());
        wrapper.like(StrUtil.isNotBlank(wmsAllocationBill.getNewWarehouseName()), WmsAllocationBillEntity::getNewWarehouseName, wmsAllocationBill.getNewWarehouseName());
        return R.ok(wmsAllocationBillService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsAllocationBillEntity::getAllocationState, Collectors.counting())));
    }


    /**
     * 通过id查询调拨单
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_view')")
    public R<WmsAllocationBillVO> getById(@PathVariable("id") Long id) {
        return R.ok(wmsAllocationBillService.getDetails(id));
    }

    /**
     * 新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Operation(summary = "新增调拨单", description = "新增调拨单")
    @SysLog("新增调拨单")
    @PostMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_add')")
    public R<Boolean> save(@RequestBody WmsAllocationBillAddDTO wmsAllocationBillAdd) {
        return R.ok(wmsAllocationBillService.saveAllocationBill(wmsAllocationBillAdd));
    }

    /**
     * 调拨接收
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Operation(summary = "调拨接收", description = "调拨接收")
    @SysLog("调拨接收")
    @PutMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_edit')")
    public R<Boolean> updateById(@RequestBody WmsAllocationBillAddDTO wmsAllocationBillAdd) {
        return R.ok(wmsAllocationBillService.updateAllocationBill(wmsAllocationBillAdd));
    }

    @Operation(summary = "取消在途调拨", description = "将新模式调拨的在途库存按原批次和质量属性返还源仓")
    @SysLog("取消在途调拨")
    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_cancel_*','wms_wmsAllocationBill_cancel')")
    public R<Boolean> cancel(@RequestBody WmsAllocationCancelDTO command) {
        return R.ok(wmsAllocationBillService.cancel(command));
    }

    @Operation(summary = "Agent跨仓调拨建议", description = "按安全库存、活动预留、质量效期和目标仓在途计算可执行建议")
    @PostMapping("/agent/transfer/recommendations")
    @PreAuthorize("@pms.hasPermission('agent:shortage:run')")
    public R<List<WmsCrossWarehouseTransferDTO.Recommendation>> transferRecommendations(
            @RequestBody WmsCrossWarehouseTransferDTO.Query query) {
        return R.ok(wmsAllocationBillService.transferRecommendations(query));
    }

    @Operation(summary = "执行已审批的Agent跨仓调拨", description = "重新锁库复核后发起正式在途调拨单")
    @PostMapping("/agent/transfer/execute")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute')")
    public R<WmsCrossWarehouseTransferDTO.Receipt> executeAgentTransfer(
            @RequestBody WmsCrossWarehouseTransferDTO.AgentCommand command) {
        return R.ok(wmsAllocationBillService.executeAgentTransfer(command));
    }

    /**
     * app-新增调拨单
     *
     * @param wmsAllocationBillAdd 调拨单接收DTO
     * @return R
     */
    @Operation(summary = "app-新增调拨单", description = "app-新增调拨单")
    @SysLog("app-新增调拨单")
    @PostMapping("/app")
    public R<Boolean> appSave(@RequestBody @Valid WmsAllocationBillAppDTO wmsAllocationBillAdd) {
        return R.ok(wmsAllocationBillService.appSaveAllocationBill(wmsAllocationBillAdd));
    }

    /**
     * 通过id删除调拨单
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除调拨单", description = "通过id删除调拨单")
    @SysLog("通过id删除调拨单")
    @DeleteMapping
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        throw new IllegalStateException("调拨单属于库存审计凭证，禁止物理删除；在途单请使用有原因取消");
    }

    /**
     * 导出excel 表格
     *
     * @param wmsAllocationBill 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
//    @PreAuthorize("@pms.hasPermission('wms_wmsAllocationBill_export')" )
    public List<WmsAllocationBillExcelVO> export(WmsAllocationBillEntity wmsAllocationBill, Long[] ids) {
        WmsAllocationBillQueryDTO scope = new WmsAllocationBillQueryDTO();
        scope.setOldWarehouseCode(wmsAllocationBill.getOldWarehouseCode());
        scope.setNewWarehouseCode(wmsAllocationBill.getNewWarehouseCode());
        requireWarehouseScope(scope);
        return wmsAllocationBillService.export(wmsAllocationBill, ids);
    }

    private void requireWarehouseScope(WmsAllocationBillQueryDTO query) {
        String source = StrUtil.trim(query.getOldWarehouseCode());
        String target = StrUtil.trim(query.getNewWarehouseCode());
        boolean sourceAllowed = StrUtil.isNotBlank(source) && permissionService.hasPermission(
                "wms_wmsAllocationBill_view", "wms_wmsAllocationBill_view_" + source);
        boolean targetAllowed = StrUtil.isNotBlank(target) && permissionService.hasPermission(
                "wms_wmsAllocationReceive_view", "wms_wmsAllocationReceive_view_" + target,
                "wms_wmsAllocationReceive_rec_" + target);
        if (!sourceAllowed && !targetAllowed) {
            throw new CheckedException("必须指定并拥有源仓调拨或目标仓接收查看权限");
        }
    }
}
