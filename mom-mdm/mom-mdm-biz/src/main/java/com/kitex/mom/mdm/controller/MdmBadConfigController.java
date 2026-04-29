package com.kitex.mom.mdm.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.mdm.entity.MdmBadConfigEntity;
import com.kitex.mom.mdm.service.MdmBadConfigService;
import com.kitex.mom.mdm.vo.excel.MdmBadExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 不良品项配置
 *
 * @author kitex
 * @since 2025-05-17
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmBad")
@Tag(description = "mdmBad", name = "不良品项管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmBadConfigController {

    private final MdmBadConfigService mdmBadConfigService;

    /**
     * 获取不良品项树形结构
     */
    @Operation(summary = "获取不良品项树形结构", description = "获取不良品项树形结构")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdmBad:show')")
    public R<List<MdmBadConfigEntity>> getPage(MdmBadConfigEntity mdmBadConfig) {
        return R.ok(mdmBadConfigService.tree(mdmBadConfig));
    }


    /**
     * 通过id查询工装分类表（设备）
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdmBad:show')")
    public R<MdmBadConfigEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmBadConfigService.getById(id));
    }

    /**
     * 新增不良品项
     */
    @Operation(summary = "新增不良品项", description = "新增不良品项")
    @PostMapping("/add")
    @PreAuthorize("@pms.hasPermission('mdmBad:add')")
    public R<Void> addData(@RequestBody MdmBadConfigEntity mdmBadConfig) {
        mdmBadConfigService.add(mdmBadConfig);
        return R.ok();
    }

    /**
     * 编辑不良品项
     */
    @Operation(summary = "编辑不良品项", description = "编辑不良品项")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdmBad:edit')")
    public R<Void> editData(@RequestBody MdmBadConfigEntity mdmBadConfig) {
        mdmBadConfigService.updateById(mdmBadConfig);
        return R.ok();
    }

    /**
     * 删除不良品项
     */
    @Operation(summary = "删除不良品项", description = "删除不良品项")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdmBad:delete')")
    public R<Void> delete(@RequestBody Long id) {
        mdmBadConfigService.removeById(id);
        return R.ok();
    }

    /**
     * 导出excel 表格
     *
     * @param mdmBadConfig 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdmBad:export')")
    public List<MdmBadExcelVO> exportExcel(MdmBadConfigEntity mdmBadConfig, Long[] ids) {
        return mdmBadConfigService
                .list(Wrappers.lambdaQuery(mdmBadConfig).in(ArrayUtil.isNotEmpty(ids), MdmBadConfigEntity::getId, ids))
                .stream()
                .map(item -> {
                    MdmBadExcelVO excelVO = new MdmBadExcelVO();
                    BeanUtil.copyProperties(item, excelVO);
                    return excelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 导入excel 表
     *
     * @param list          对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdmBad:import')")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<MdmBadExcelVO> list, BindingResult bindingResult) {
        return mdmBadConfigService.importExcel(list, bindingResult);
    }

}
