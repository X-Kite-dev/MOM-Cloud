package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmTimePieceEntity;
import com.kitex.mom.mdm.service.MdmTimePieceService;
import com.kitex.mom.mdm.vo.excel.MdmTimePieceExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 计时计件表
 *
 * @author kitex
 * @date 2025-09-03 17:23:53
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmTimePiece")
@Tag(description = "mdmTimePiece", name = "计时计件表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmTimePieceController {

    private final MdmTimePieceService mdmTimePieceService;

    /**
     * 分页查询
     *
     * @param page         分页对象
     * @param mdmTimePiece 计时计件表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTimePiece_view')")
    public R getMdmTimePiecePage(@ParameterObject SearchPage page, @ParameterObject MdmTimePieceEntity mdmTimePiece) {
        LambdaQueryWrapper<MdmTimePieceEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(mdmTimePiece.getMatCode()), MdmTimePieceEntity::getMatCode, mdmTimePiece.getMatCode())
                .like(StrUtil.isNotBlank(mdmTimePiece.getMatName()), MdmTimePieceEntity::getMatName, mdmTimePiece.getMatName())
                .like(StrUtil.isNotBlank(mdmTimePiece.getMatSpec()), MdmTimePieceEntity::getMatSpec, mdmTimePiece.getMatSpec())
                .like(StrUtil.isNotBlank(mdmTimePiece.getProcedureCode()), MdmTimePieceEntity::getProcedureCode, mdmTimePiece.getProcedureCode())
                .like(StrUtil.isNotBlank(mdmTimePiece.getProcedureName()), MdmTimePieceEntity::getProcedureName, mdmTimePiece.getProcedureName())
                .like(StrUtil.isNotBlank(mdmTimePiece.getType()), MdmTimePieceEntity::getType, mdmTimePiece.getType())
                .eq(Objects.nonNull(mdmTimePiece.getStatus()), MdmTimePieceEntity::getStatus, mdmTimePiece.getStatus())
                .orderByDesc(MdmTimePieceEntity::getMatCode).orderByDesc(MdmTimePieceEntity::getCreateTime);
        return R.ok(mdmTimePieceService.page(page.toMpPage(), wrapper));
    }

    /**
     * 统计计时计件状态数量
     *
     * @param mdmTimePiece 计时计件表
     * @return
     */
    @Operation(summary = "统计计时计件状态数量", description = "统计计时计件状态数量")
    @GetMapping("/getCount")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTimePiece_view')")
    public R<Map<Boolean, Long>> getMdmTimePieceCount(@ParameterObject MdmTimePieceEntity mdmTimePiece) {
        LambdaQueryWrapper<MdmTimePieceEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.like(StrUtil.isNotBlank(mdmTimePiece.getMatCode()), MdmTimePieceEntity::getMatCode, mdmTimePiece.getMatCode())
                .like(StrUtil.isNotBlank(mdmTimePiece.getMatName()), MdmTimePieceEntity::getMatName, mdmTimePiece.getMatName())
                .like(StrUtil.isNotBlank(mdmTimePiece.getMatSpec()), MdmTimePieceEntity::getMatSpec, mdmTimePiece.getMatSpec())
                .like(StrUtil.isNotBlank(mdmTimePiece.getProcedureCode()), MdmTimePieceEntity::getProcedureCode, mdmTimePiece.getProcedureCode())
                .like(StrUtil.isNotBlank(mdmTimePiece.getProcedureName()), MdmTimePieceEntity::getProcedureName, mdmTimePiece.getProcedureName())
                .like(StrUtil.isNotBlank(mdmTimePiece.getType()), MdmTimePieceEntity::getType, mdmTimePiece.getType());
        return R.ok(mdmTimePieceService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmTimePieceEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过id查询计时计件表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTimePiece_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mdmTimePieceService.getById(id));
    }

    /**
     * 新增计时计件表
     *
     * @param mdmTimePiece 计时计件表
     * @return R
     */
    @Operation(summary = "新增计时计件表", description = "新增计时计件表")
    @SysLog("新增计时计件表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmTimePiece_add')")
    public R save(@RequestBody MdmTimePieceEntity mdmTimePiece) {
        return R.ok(mdmTimePieceService.saveTimePiece(mdmTimePiece));
    }

    /**
     * 修改计时计件表
     *
     * @param mdmTimePiece 计时计件表
     * @return R
     */
    @Operation(summary = "修改计时计件表", description = "修改计时计件表")
    @SysLog("修改计时计件表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmTimePiece_edit')")
    public R updateById(@RequestBody MdmTimePieceEntity mdmTimePiece) {
        return R.ok(mdmTimePieceService.updateTimePiece(mdmTimePiece));
    }

    /**
     * 通过id删除计时计件表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除计时计件表", description = "通过id删除计时计件表")
    @SysLog("通过id删除计时计件表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmTimePiece_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(mdmTimePieceService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmTimePiece 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmTimePiece_export')")
    public List<MdmTimePieceExcelVO> exportTimePiece(MdmTimePieceEntity mdmTimePiece, Long[] ids) {
        return mdmTimePieceService.exportTimePiece(mdmTimePiece, ids);
    }

    /**
     * 导入计时计件数据
     *
     * @param excelVOList
     * @param bindingResult
     * @return
     */
    @Operation(summary = "导入计时计件数据")
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_factoryModel_export')")
    public R importTimePiece(@RequestExcel(headRowNumber = 2) List<MdmTimePieceExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmTimePieceService.importTimePiece(excelVOList, bindingResult);
    }
}