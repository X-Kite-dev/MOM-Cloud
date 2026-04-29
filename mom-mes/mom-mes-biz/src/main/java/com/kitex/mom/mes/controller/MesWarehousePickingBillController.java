package com.kitex.mom.mes.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesPickingReceiveMatDTO;
import com.kitex.mom.mes.dto.MesPickingWmsOutBillBackDTO;
import com.kitex.mom.mes.entity.MesWarehousePickingBillEntity;
import com.kitex.mom.mes.service.MesWarehousePickingBillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 生产领料单
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehousePickingBill")
@Tag(description = "mesWarehousePickingBill", name = "生产领料单管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehousePickingBillController {

    private final MesWarehousePickingBillService mesWarehousePickingBillService;

    /**
     * 分页查询
     *
     * @param page                    分页对象
     * @param mesWarehousePickingBill 生产领料单
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_view')")
    public R getMesWarehousePickingBillPage(@ParameterObject SearchPage page, @ParameterObject MesWarehousePickingBillEntity mesWarehousePickingBill) {
        QueryWrapper<MesWarehousePickingBillEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getBillNo()), MesWarehousePickingBillEntity::getBillNo, mesWarehousePickingBill.getBillNo());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getMatCode()), MesWarehousePickingBillEntity::getMatCode, mesWarehousePickingBill.getMatCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getMatName()), MesWarehousePickingBillEntity::getMatName, mesWarehousePickingBill.getMatName());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getMatSpec()), MesWarehousePickingBillEntity::getMatSpec, mesWarehousePickingBill.getMatSpec());
        wrapper.lambda().eq(StrUtil.isNotBlank(mesWarehousePickingBill.getStatus()), MesWarehousePickingBillEntity::getStatus, mesWarehousePickingBill.getStatus());
        wrapper.lambda().orderByAsc(MesWarehousePickingBillEntity::getStatus);
        wrapper.lambda().orderByDesc(MesWarehousePickingBillEntity::getCreateTime);
        return R.ok(mesWarehousePickingBillService.page(page.toMpPage(), wrapper));
    }


    @Operation(summary = "查询质检任务状态的数量", description = "查询各质检任务状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject MesWarehousePickingBillEntity mesWarehousePickingBill) {
        QueryWrapper<MesWarehousePickingBillEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getBillNo()), MesWarehousePickingBillEntity::getBillNo, mesWarehousePickingBill.getBillNo());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getMatCode()), MesWarehousePickingBillEntity::getMatCode, mesWarehousePickingBill.getMatCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getMatName()), MesWarehousePickingBillEntity::getMatName, mesWarehousePickingBill.getMatName());
        wrapper.lambda().like(StrUtil.isNotBlank(mesWarehousePickingBill.getMatSpec()), MesWarehousePickingBillEntity::getMatSpec, mesWarehousePickingBill.getMatSpec());
        wrapper.lambda().orderByAsc(MesWarehousePickingBillEntity::getStatus);
        wrapper.lambda().orderByDesc(MesWarehousePickingBillEntity::getCreateTime);
        return R.ok(mesWarehousePickingBillService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MesWarehousePickingBillEntity::getStatus, Collectors.counting())));
    }


    /**
     * 通过条件查询生产领料单
     *
     * @param mesWarehousePickingBill 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_view')")
    public R getDetails(@ParameterObject MesWarehousePickingBillEntity mesWarehousePickingBill) {
        return R.ok(mesWarehousePickingBillService.listDeep(Wrappers.query(mesWarehousePickingBill)));
    }

    @Operation(summary = "查询列表", description = "查询列表")
    @GetMapping("/list")
    public R<List<MesWarehousePickingBillEntity>> queryList(MesWarehousePickingBillEntity query) {
        return R.ok(mesWarehousePickingBillService.list(Wrappers.query(query)));
    }

    /**
     * 新增生产领料单
     *
     * @param mesWarehousePickingBill 生产领料单
     * @return R
     */
    @Operation(summary = "新增生产领料单", description = "新增生产领料单")
    @SysLog("新增生产领料单")
    @PostMapping
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_add')")
    public R save(@Validated @RequestBody MesWarehousePickingBillEntity mesWarehousePickingBill) {
        return R.ok(mesWarehousePickingBillService.saveDeep(mesWarehousePickingBill));
    }

    /**
     * 修改生产领料单
     *
     * @param mesWarehousePickingBill 生产领料单
     * @return R
     */
    @Operation(summary = "修改生产领料单", description = "修改生产领料单")
    @SysLog("修改生产领料单")
    @PutMapping
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_edit')")
    public R updateById(@RequestBody MesWarehousePickingBillEntity mesWarehousePickingBill) {
        return R.ok(mesWarehousePickingBillService.updateDeep(mesWarehousePickingBill));
    }

    /**
     * 提交生产领料单
     *
     * @param id 生产领料单
     * @return R
     */
    @Operation(summary = "提交生产领料单", description = "提交生产领料单")
    @SysLog("提交生产领料单")
    @GetMapping("/commitBill")
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_edit')")
    public R commitBill(@RequestParam Long id) {
        return R.ok(mesWarehousePickingBillService.commitBill(id));
    }

    /**
     * 撤回领料出库单
     *
     * @param id 生产领料单
     * @return R
     */
    @Operation(summary = "撤回领料出库单", description = "撤回领料出库单")
    @SysLog("撤回领料出库单")
    @GetMapping("/withdraw")
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_edit')")
    public R withdraw(@RequestParam Long id) {
        return R.ok(mesWarehousePickingBillService.withdraw(id));
    }

    /**
     * 领料出库单完成后返回结果
     *
     * @param dto 生产领料单
     * @return R
     */
    @Operation(summary = "领料出库单完成后返回结果", description = "领料出库单完成后返回结果")
    @SysLog("领料出库单完成后返回结果")
    @PostMapping("/wmsOutBillBack")
    public R<Boolean> wmsOutBillBack(@RequestBody MesPickingWmsOutBillBackDTO dto) {
        return R.ok(mesWarehousePickingBillService.wmsOutBillBack(dto));
    }

    /**
     * 领料出库单 接收物料
     *
     * @param dto 生产领料单
     * @return R
     */
    @Operation(summary = "领料出库单 接收物料", description = "领料出库单 接收物料")
    @SysLog("领料出库单 接收物料")
    @PostMapping("/receiveBill")
    public R<Boolean> receiveBill(@RequestBody MesPickingReceiveMatDTO dto) {
        return R.ok(mesWarehousePickingBillService.receiveBill(dto));
    }

    /**
     * 通过id删除生产领料单
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除生产领料单", description = "通过id删除生产领料单")
    @SysLog("通过id删除生产领料单")
    @DeleteMapping
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(mesWarehousePickingBillService.removeDeep(ids));
    }

    /**
     * 通过id删除生产领料单子表数据
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除生产领料单子表数据", description = "通过id删除生产领料单子表数据")
    @SysLog("通过id删除生产领料单子表数据")
    @DeleteMapping("/child")
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_del')")
    public R removeChild(@RequestBody Long[] ids) {
        return R.ok(mesWarehousePickingBillService.removeChild(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param mesWarehousePickingBill 查询条件
     * @param ids                     导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_export')")
    public List<MesWarehousePickingBillEntity> exportExcel(MesWarehousePickingBillEntity mesWarehousePickingBill, Long[] ids) {
        return mesWarehousePickingBillService.list(Wrappers.lambdaQuery(mesWarehousePickingBill).in(ArrayUtil.isNotEmpty(ids), MesWarehousePickingBillEntity::getId, ids));
    }

    /**
     * 导入excel 表
     *
     * @param list          对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
//	@PreAuthorize("@pms.hasPermission('mes_mesWarehousePickingBill_export')")
    public R importExcel(@RequestExcel List<MesWarehousePickingBillEntity> list, BindingResult bindingResult) {
        return R.ok(mesWarehousePickingBillService.saveBatch(list));
    }
}