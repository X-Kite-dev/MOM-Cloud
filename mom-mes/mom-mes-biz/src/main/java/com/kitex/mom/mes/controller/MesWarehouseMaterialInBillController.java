package com.kitex.mom.mes.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.entity.MesWarehouseMaterialInBillEntity;
import com.kitex.mom.mes.service.MesWarehouseMaterialInBillService;
import com.kitex.mom.mes.vo.MesWarehouseMaterialInBillExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 生产领料
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseMaterialInBill")
@Tag(description = "mesWarehouseMaterialInBill", name = "生产领料管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseMaterialInBillController {

    private final MesWarehouseMaterialInBillService mesWarehouseMaterialInBillService;

    /**
     * 分页查询
     *
     * @param page   分页对象
     * @param entity 生产领料
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseMaterialInBill_view')")
    public R getMesWarehouseMaterialInBillPage(@ParameterObject SearchPage page, @ParameterObject MesWarehouseMaterialInBillEntity entity) {
        LambdaQueryWrapper<MesWarehouseMaterialInBillEntity> wrapper = getWrapper(entity);
        return R.ok(mesWarehouseMaterialInBillService.page(page.toMpPage(), wrapper));
    }

    public LambdaQueryWrapper<MesWarehouseMaterialInBillEntity> getWrapper(MesWarehouseMaterialInBillEntity entity) {
        LambdaQueryWrapper<MesWarehouseMaterialInBillEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(entity.getBillNo()), MesWarehouseMaterialInBillEntity::getBillNo, entity.getBillNo());
        wrapper.like(StrUtil.isNotBlank(entity.getResourceNo()), MesWarehouseMaterialInBillEntity::getResourceNo, entity.getResourceNo());
        wrapper.like(StrUtil.isNotBlank(entity.getMatCode()), MesWarehouseMaterialInBillEntity::getMatCode, entity.getMatCode());
        wrapper.like(StrUtil.isNotBlank(entity.getMatName()), MesWarehouseMaterialInBillEntity::getMatName, entity.getMatName());
        wrapper.eq(StrUtil.isNotBlank(entity.getStatus()), MesWarehouseMaterialInBillEntity::getStatus, entity.getStatus());
        return wrapper;
    }


    /**
     * 通过id查询生产领料
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseMaterialInBill_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mesWarehouseMaterialInBillService.getById(id));
    }

    /**
     * 直接新增并入库
     *
     * @param mesWarehouseMaterialInBill 生产领料
     * @return R
     */
    @Operation(summary = "直接新增并入库", description = "直接新增并入库")
    @SysLog("直接新增并入库")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseMaterialInBill_add')")
    public R save(@RequestBody MesWarehouseMaterialInBillEntity mesWarehouseMaterialInBill) {
        return R.ok(mesWarehouseMaterialInBillService.saveRecord(mesWarehouseMaterialInBill));
    }

    /**
     * 新增生产领料-通过出库单
     *
     * @param ids 出库单id
     * @return R
     */
    @Operation(summary = "新增生产领料-通过出库单", description = "新增生产领料-通过出库单")
    @SysLog("新增生产领料-通过出库单")
    @PostMapping("/saveByWmsOutBill")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseMaterialInBill_add') or @pms.hasPermission('mes_materialReceipt_import')")
    public R saveByWmsOutBill(@RequestBody List<Long> ids) {
        return R.ok(mesWarehouseMaterialInBillService.saveByWmsOutBill(ids));
    }

    /**
     * 设置库位并入库
     *
     * @param list 生产领料
     * @return R
     */
    @Operation(summary = "设置库位并入库", description = "设置库位并入库")
    @SysLog("设置库位并入库")
    @PostMapping("/inStock")
    @PreAuthorize("@pms.hasPermission('mes_materialReceipt_receive')")
    public R inStock(@RequestBody MesWarehouseMaterialInBillEntity list) {
        return R.ok(mesWarehouseMaterialInBillService.inStock(list));
    }

    /**
     * 修改生产领料
     *
     * @param mesWarehouseMaterialInBill 生产领料
     * @return R
     */
    @Operation(summary = "修改生产领料", description = "修改生产领料")
    @SysLog("修改生产领料")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseMaterialInBill_edit')")
    public R updateById(@RequestBody MesWarehouseMaterialInBillEntity mesWarehouseMaterialInBill) {
        throw new com.kitex.common.core.exception.CheckedException("收料来源及数量不可直接修改；签收错误请通过异岗复核冲销后重新签收");
    }

    /**
     * 通过id删除生产领料
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除生产领料", description = "通过id删除生产领料")
    @SysLog("通过id删除生产领料")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseMaterialInBill_del')")
    public R removeById(@RequestBody Long[] ids) {
        throw new com.kitex.common.core.exception.CheckedException("收料通知是WMS发料事实，不可直接删除；请通过原业务退料或签收冲销处理");
    }


    /**
     * 导出excel 表格
     *
     * @param entity 查询条件
     * @param ids    导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mes_mesWarehouseMaterialInBill_export')")
    public List<MesWarehouseMaterialInBillExcelVO> export(MesWarehouseMaterialInBillEntity entity, Long[] ids) {
        return mesWarehouseMaterialInBillService.listExport(getWrapper(entity).in(ArrayUtil.isNotEmpty(ids), MesWarehouseMaterialInBillEntity::getId, ids));
    }
}
