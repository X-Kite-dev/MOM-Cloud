package com.kitex.mom.pms.controller;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.pms.dto.PmsSupplierDTO;
import com.kitex.mom.pms.entity.PmsSupplierDetailEntity;
import com.kitex.mom.pms.service.PmsSupplierDetailService;
import com.kitex.mom.pms.vo.PmsSupplierVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 供应商详情表
 *
 * @author kitex
 * @date 2025-03-19 08:56:36
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsSupplierDetail")
@Tag(description = "pmsSupplierDetail", name = "供应商详情表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsSupplierDetailController {

    private final PmsSupplierDetailService pmsSupplierDetailService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmSupplier 供应商表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<Page<MdmSupplierEntity>> getPmsSupplierDetailPage(@ParameterObject SearchPage page, @ParameterObject MdmSupplierEntity mdmSupplier) {
        return R.ok(pmsSupplierDetailService.getSupplierPage(page.toMpPage(), mdmSupplier));
    }


    /**
     * 通过id查询供应商详情表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R<PmsSupplierVO> getById(@PathVariable("id") Long id) {
        return R.ok(pmsSupplierDetailService.getSupplierById(id));
    }

    /**
     * 新增供应商
     *
     * @param pmsSupplierDTO 供应商DTO
     * @return R
     */
    @Operation(summary = "新增供应商", description = "新增供应商")
    @SysLog("新增供应商")
    @PostMapping
    public R<Boolean> save(@RequestBody PmsSupplierDTO pmsSupplierDTO) {
        return R.ok(pmsSupplierDetailService.saveSupplier(pmsSupplierDTO));
    }

    /**
     * 修改供应商表
     *
     * @param pmsSupplierDTO 供应商表
     * @return R
     */
    @Operation(summary = "修改供应商表", description = "修改供应商表")
    @SysLog("修改供应商表")
    @PutMapping
    public R<Boolean> updateById(@RequestBody PmsSupplierDTO pmsSupplierDTO) {
        return R.ok(pmsSupplierDetailService.updateSupplierById(pmsSupplierDTO));
    }

    /**
     * 通过id删除供应商详情表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除供应商详情表", description = "通过id删除供应商详情表")
    @SysLog("通过id删除供应商详情表")
    @DeleteMapping
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsSupplierDetailService.removeBatchSupplier(ids));
    }


    /**
     * 导出excel 表格
     *
     * @param pmsSupplierDetail 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    public List<PmsSupplierDetailEntity> export(PmsSupplierDetailEntity pmsSupplierDetail, Long[] ids) {
        return pmsSupplierDetailService.list(Wrappers.lambdaQuery(pmsSupplierDetail).in(ArrayUtil.isNotEmpty(ids), PmsSupplierDetailEntity::getId, ids));
    }
}