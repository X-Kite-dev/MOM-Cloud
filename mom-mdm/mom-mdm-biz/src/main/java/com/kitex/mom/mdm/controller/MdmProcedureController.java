package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmProcedureEntity;
import com.kitex.mom.mdm.service.MdmProcedureService;
import com.kitex.mom.mdm.vo.excel.MdmWorkingProcedureExcelVO;
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
import java.util.stream.Collectors;

/**
 * 工序管理 Controller - 提供工序信息的增删改查接口
 * 用于管理生产工艺过程中的各个工序信息
 *
 * @author kitex
 * @date 2024-10-31 17:16:43
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmProcedure")
@Tag(description = "mdmProcedure", name = "工序表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmProcedureController {

    private final MdmProcedureService mdmProcedureService;

    /**
     * 分页查询工序信息
     *
     * @param page                分页对象，包含分页参数
     * @param mdmWorkingProcedure 查询条件，支持工序编码、名称、状态等过滤
     * @return 分页查询结果，包含工序列表和分页信息
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_view')")
    public R<Page<MdmProcedureEntity>> getMdmWorkingProcedurePage(SearchPage page,
                                                                  @ParameterObject MdmProcedureEntity mdmWorkingProcedure) {
        QueryWrapper<MdmProcedureEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmWorkingProcedure.getProcessCode()),
                MdmProcedureEntity::getProcessCode, mdmWorkingProcedure.getProcessCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmWorkingProcedure.getProcessName()),
                MdmProcedureEntity::getProcessName, mdmWorkingProcedure.getProcessName());
        wrapper.lambda().eq(ObjUtil.isNotNull(mdmWorkingProcedure.getStatus()), MdmProcedureEntity::getStatus,
                mdmWorkingProcedure.getStatus());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmWorkingProcedure.getOperationName()),
                MdmProcedureEntity::getOperationName, mdmWorkingProcedure.getOperationName());
        wrapper.lambda().orderByDesc(MdmProcedureEntity::getCreateTime);
        return R.ok(mdmProcedureService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询启用的工序列表
     *
     * @param workingProcedure 查询条件，支持工序编码、名称过滤
     * @return 启用状态的工序列表
     */
    @Operation(summary = "查询工序列表", description = "查询工序列表")
    @GetMapping("/list")
    public R<List<MdmProcedureEntity>> getList(@ParameterObject MdmProcedureEntity workingProcedure) {
        QueryWrapper<MdmProcedureEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda()
                .eq(MdmProcedureEntity::getStatus, Boolean.TRUE)
                .like(StrUtil.isNotBlank(workingProcedure.getProcessCode()),
                        MdmProcedureEntity::getProcessCode, workingProcedure.getProcessCode())
                .like(StrUtil.isNotBlank(workingProcedure.getProcessName()),
                        MdmProcedureEntity::getProcessName, workingProcedure.getProcessName());
        return R.ok(mdmProcedureService.list(queryWrapper));
    }

    /**
     * 统计各工序状态的数量
     *
     * @param mdmWorkingProcedure 查询条件，支持工序编码、名称、作业名称过滤
     * @return 按状态分组的工序数量统计（true-启用，false-禁用）
     */
    @Operation(summary = "查询各工序状态的数量", description = "查询各工序状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmProcedureEntity mdmWorkingProcedure) {
        QueryWrapper<MdmProcedureEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmProcedureEntity::getStatus)
                .like(StrUtil.isNotBlank(mdmWorkingProcedure.getProcessCode()), MdmProcedureEntity::getProcessCode, mdmWorkingProcedure.getProcessCode())
                .like(StrUtil.isNotBlank(mdmWorkingProcedure.getProcessName()), MdmProcedureEntity::getProcessName, mdmWorkingProcedure.getProcessName())
                .like(StrUtil.isNotBlank(mdmWorkingProcedure.getOperationName()), MdmProcedureEntity::getOperationName, mdmWorkingProcedure.getOperationName());
        return R.ok(mdmProcedureService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmProcedureEntity::getStatus, Collectors.counting())));
    }

    /**
     * 根据ID查询工序详情
     *
     * @param id 工序ID
     * @return 工序详细信息
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_view')")
    public R<MdmProcedureEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmProcedureService.getById(id));
    }

    /**
     * 根据工序编码查询工序详情
     *
     * @param processCode 工序编码
     * @return 工序详细信息
     */
    @Operation(summary = "根据工序编码获取详情", description = "根据工序编码获取详情")
    @GetMapping("/details")
    public R<MdmProcedureEntity> details(String processCode) {
        return R.ok(mdmProcedureService.getOne(Wrappers.<MdmProcedureEntity>lambdaQuery()
                .eq(MdmProcedureEntity::getProcessCode, processCode)));
    }

    /**
     * 新增工序表
     *
     * @param mdmWorkingProcedure 工序表
     * @return R
     */
    @Operation(summary = "新增工序表", description = "新增工序表")
    @SysLog("新增工序表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_add')")
    public R<Boolean> save(@RequestBody MdmProcedureEntity mdmWorkingProcedure) {
        return R.ok(mdmProcedureService.save(mdmWorkingProcedure));
    }

    /**
     * 修改工序表
     *
     * @param mdmWorkingProcedure 工序表
     * @return R
     */
    @Operation(summary = "修改工序表", description = "修改工序表")
    @SysLog("修改工序表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_edit')")
    public R<Boolean> updateById(@RequestBody MdmProcedureEntity mdmWorkingProcedure) {
        return R.ok(mdmProcedureService.updateById(mdmWorkingProcedure));
    }

    /**
     * 通过id删除工序表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除工序表", description = "通过id删除工序表")
    @SysLog("通过id删除工序表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmProcedureService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   工序列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_export')")
    public R importWorkingProcedure(@RequestExcel(headRowNumber = 2) List<MdmWorkingProcedureExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmProcedureService.importWorkingProcedure(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmWorkingProcedure 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkingProcedure_export')")
    public List<MdmWorkingProcedureExcelVO> export(MdmProcedureEntity mdmWorkingProcedure, Long[] ids) {
        return mdmProcedureService.export(mdmWorkingProcedure, ids);
    }

}