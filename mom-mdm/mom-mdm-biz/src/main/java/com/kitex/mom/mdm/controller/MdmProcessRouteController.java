package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.entity.MdmProcessProcedureSettingEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.service.MdmProcessRouteService;
import com.kitex.mom.mdm.vo.MdmProcedureSopVO;
import com.kitex.mom.mdm.vo.MdmProcessRouteProcedureVO;
import com.kitex.mom.mdm.vo.MdmProcessRouteTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmProcessRouteExcelVO;
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

/**
 * 工艺路线
 *
 * @author kitex
 * @date 2024-10-30 14:36:59
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmProcessRoute")
@Tag(description = "mdmProcessRoute", name = "工艺路线管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmProcessRouteController {

    private final MdmProcessRouteService mdmProcessRouteService;

    /**
     * 根据产品编码查询默认启用工艺路线
     *
     * @param productCode 产品编码
     * @return R
     */
    @Operation(summary = "根据产品编码查询默认启用工艺路线", description = "根据产品编码查询默认启用工艺路线")
    @GetMapping("/getDefaultByProductCode")
    public R<MdmProcessRouteEntity> getDefaultByProductCode(@RequestParam("productCode") String productCode) {
        return R.ok(mdmProcessRouteService.getOne(
                Wrappers.<MdmProcessRouteEntity>lambdaQuery()
                        .eq(MdmProcessRouteEntity::getProductCode, productCode)
                        .eq(MdmProcessRouteEntity::getIsDefault, true)
                        .eq(MdmProcessRouteEntity::getStatus, true)
                        .last("LIMIT 1")));
    }

    /**
     * 根据产品编码查询工艺路线列表
     *
     * @param productCode 产品编码
     * @return
     */
    @Operation(summary = "根据产品编码查询工艺路线列表", description = "根据产品编码查询工艺路线列表")
    @GetMapping("/list")
    public R<List<MdmProcessRouteEntity>> getMdmProcessRouteListByProductCode(@RequestParam("productCode") String productCode) {
        return R.ok(mdmProcessRouteService.list(new LambdaQueryWrapper<MdmProcessRouteEntity>().eq(MdmProcessRouteEntity::getProductCode, productCode)));
    }

    @Operation(summary = "批量查询工序投料映射", description = "供PLAN和Agent按工艺路线、物料批量核算缺料影响工序")
    @PostMapping("/materialAssignments")
    public R<List<MdmProcessProcedureMatEntity>> getMaterialAssignments(@RequestBody MdmRouteMaterialAssignmentQueryDTO query) {
        return R.ok(mdmProcessRouteService.getMaterialAssignments(query));
    }

    /**
     * 工艺路线树查询
     *
     * @param mdmProcessRoute 工艺路线
     * @return
     */
    @Operation(summary = "工艺路线列表查询", description = "工艺路线树查询")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_view')")
    public R<List<MdmProcessRouteTreeVO>> getMdmProcessRouteTree(@ParameterObject MdmProcessRouteEntity mdmProcessRoute) {
        QueryWrapper<MdmProcessRouteEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().and(StrUtil.isNotEmpty(mdmProcessRoute.getProductName()), wrapper -> wrapper
                        .like(MdmProcessRouteEntity::getProductCode, mdmProcessRoute.getProductName())
                        .or()
                        .like(MdmProcessRouteEntity::getProductName, mdmProcessRoute.getProductName()))
                .orderByDesc(MdmProcessRouteEntity::getCreateTime);
        return R.ok(mdmProcessRouteService.tree(queryWrapper));
    }

    /**
     * 根据工艺路线编码查询工序列表
     *
     * @param routeCode 工艺路线编码
     * @return R
     */
    @Operation(summary = "根据工艺路线编码查询工序列表", description = "根据工艺路线编码查询工序列表")
    @GetMapping("/getProcedureList")
    public R<List<MdmProcessRouteWorkingEntity>> getProcedureList(@RequestParam("routeCode") String routeCode) {
        return R.ok(mdmProcessRouteService.getProcedureList(routeCode));
    }

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/selectPage")
    public R<Page<MdmProcessRouteEntity>> getMdmProcessRoutePageByProduct(SearchPage page, @ParameterObject MdmProcessRouteSelectDTO mdmProcessRoute) {
        return R.ok(mdmProcessRouteService.selectPage(page.toMpPage(), mdmProcessRoute));
    }

    /**
     * 通过id查询工艺路线
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_view')")
    public R<MdmProcessRouteDTO> getById(@PathVariable("id") Long id) {
        return R.ok(mdmProcessRouteService.getRouteById(id));
    }

    @Operation(summary = "通过id查询", description = "通过id查询")
    @PostMapping("/mdmProcessRouteByIds")
    public R<List<MdmProcessRouteDTO>> getDetailByIds(@RequestBody List<Long> ids) {
        return R.ok(mdmProcessRouteService.getRouteByIds(ids));
    }

    /**
     * 通过id、线体编码查询工艺路线
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}/{lineCode}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_view')")
    public R<MdmProcessRouteDTO> getById(@PathVariable("id") Long id, @PathVariable("lineCode") String lineCode) {
        return R.ok(mdmProcessRouteService.getRouteByIdAndLineCode(id, lineCode));
    }

//    /**
//     * 根据id查询线体
//     */
//    @Operation(summary = "根据id查询线体", description = "根据id查询线体")
//    @GetMapping("/getLineById")
//    public R<Page<MdmFactoryModelEntity>> getLineById(SearchPage page) {
//        MdmFactoryModelEntity mdmFactoryModelEntity =new MdmFactoryModelEntity();
//        R<Page<MdmFactoryModelEntity>> mdmProductLinePage = mdmProductLineController.getMdmProductLinePage(page, mdmFactoryModelEntity);
//        return mdmProductLinePage;
//    }

    /**
     * 根据工艺路线编码获取详情
     *
     * @param routeCode 工艺路线编码
     * @return R
     */
    @Operation(summary = "根据工艺路线编码获取详情", description = "根据工艺路线编码获取详情")
    @GetMapping("/details")
    public R<MdmProcessRouteEntity> details(String routeCode) {
        return R.ok(mdmProcessRouteService
                .getOne(Wrappers.<MdmProcessRouteEntity>lambdaQuery().eq(MdmProcessRouteEntity::getRouteCode, routeCode)));
    }

    /**
     * 新增工艺路线
     *
     * @param mdmProcessRouteEntity 工艺路线
     * @return R
     */
    @Operation(summary = "新增工艺路线", description = "新增工艺路线")
    @SysLog("新增工艺路线")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_add')")
    public R<Boolean> save(@RequestBody MdmProcessRouteEntity mdmProcessRouteEntity) {
        return R.ok(mdmProcessRouteService.save(mdmProcessRouteEntity));
    }

    /**
     * 修改工艺路线
     *
     * @param mdmProcessRouteEntity 工艺路线
     * @return R
     */
    @Operation(summary = "修改工艺路线", description = "修改工艺路线")
    @SysLog("修改工艺路线")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_edit')")
    public R<Boolean> updateById(@RequestBody MdmProcessRouteEntity mdmProcessRouteEntity) {
        return R.ok(mdmProcessRouteService.updateById(mdmProcessRouteEntity));
    }

    /**
     * 根据id设置默认工艺路线
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "根据id设置默认工艺路线", description = "根据id设置默认工艺路线")
    @SysLog("根据id设置默认工艺路线")
    @PutMapping("/default")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_edit')")
    public R<Boolean> routeDefault(Long id) {
        return R.ok(mdmProcessRouteService.routeDefault(id));
    }

    /**
     * 通过id删除工艺路线
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除工艺路线", description = "通过id删除工艺路线")
    @SysLog("通过id删除工艺路线")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmProcessRouteService.removeRoute(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   工艺路线列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_export')")
    public R importProcessRoute(@RequestExcel(headRowNumber = 2) List<MdmProcessRouteExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmProcessRouteService.importProcessRoute(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmProcessRoute 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_export')")
    public List<MdmProcessRouteExcelVO> export(MdmProcessRouteEntity mdmProcessRoute, Long[] ids) {
        return mdmProcessRouteService.export(mdmProcessRoute, ids);
    }

    /**
     * 根据工艺路线ID查询工艺路线下的工序及工序下的工作中心
     *
     * @param routeId 工艺路线ID
     * @return R
     */
    @Operation(summary = "根据工艺路线ID查询工艺路线下的工序及工序下的工作中心", description = "根据工艺路线ID查询工艺路线下的工序及工序下的工作中心")
    @GetMapping("/getProcessWorking")
    public R<MdmProcessRouteProcedureVO> getProcessWorking(@RequestParam("routeId") Long routeId) {
        return R.ok(mdmProcessRouteService.getProcessWorking(routeId));
    }

    /**
     * 根据工艺路线ID查询工艺路线下的工序及工序下的工作中心
     *
     * @param id 工艺路线ID
     * @return R
     */
    @Operation(summary = "根据工艺路线ID查询工艺路线下的工序及工序下的工作中心", description = "根据工艺路线ID查询工艺路线下的工序及工序下的工作中心")
    @GetMapping("/getProcessWorkingById")
    public R<MdmProcessRouteWorkingEntity> getProcessWorkingById(@RequestParam("id") Long id) {
        return R.ok(mdmProcessRouteService.getProcessWorkingById(id));
    }


    /**
     * 新增工艺路线下的工序
     *
     * @param mdmProcessRouteProcedureDTO 工艺路线-工序DTO
     * @return R
     */
    @Operation(summary = "新增工艺路线下的工序", description = "新增工艺路线下的工序")
    @PutMapping("/saveProcessWorking")
    public R<Boolean> saveProcessWorking(@RequestBody MdmProcessRouteProcedureDTO mdmProcessRouteProcedureDTO) {
        return R.ok(mdmProcessRouteService.saveProcessWorking(mdmProcessRouteProcedureDTO));
    }

    /**
     * 查询工艺路线下的工序的SOP
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Operation(summary = "查询工艺路线下的工序的SOP", description = "查询工艺路线下的工序的SOP")
    @GetMapping("/getSop/{id}")
    public R<List<MdmProcedureSopVO>> getSop(@PathVariable("id") Long id) {
        return R.ok(mdmProcessRouteService.getSop(id));
    }

    /**
     * 查询工艺路线下的工序的SOP
     *
     * @param routeCode   工艺路线-编码
     * @param processCode 工序-编码
     * @return R
     */
    @Operation(summary = "查询工艺路线下的工序的SOP", description = "查询工艺路线下的工序的SOP")
    @GetMapping("/getSopByCode")
    public R<List<MdmProcedureSopVO>> getSopByCode(@RequestParam("routeCode") String routeCode, @RequestParam("processCode") String processCode) {
        return R.ok(mdmProcessRouteService.getSopByCode(routeCode, processCode));
    }

    /**
     * 新增工艺路线下的工序的SOP
     *
     * @param mdmProcessProcedureSopDTO 工艺路线-工序-SOP
     * @return R
     */
    @Operation(summary = "新增工艺路线下的工序的SOP", description = "新增工艺路线下的工序的SOP")
    @PutMapping("/saveSop")
    public R<Boolean> saveSop(@RequestBody MdmProcessProcedureSopDTO mdmProcessProcedureSopDTO) {
        return R.ok(mdmProcessRouteService.saveSop(mdmProcessProcedureSopDTO));
    }

    /**
     * 查询工艺路线下工序绑定的投料信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Operation(summary = "查询工艺路线下工序绑定的投料信息", description = "查询工艺路线下工序绑定的投料信息")
    @GetMapping("/getMat/{id}")
    public R<List<MdmProcessProcedureMatEntity>> getMat(@PathVariable("id") Long id) {
        return R.ok(mdmProcessRouteService.getMat(id));
    }

    /**
     * 查询工艺路线下工序绑定的投料信息
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工序编码
     * @return R
     */
    @Operation(summary = "查询工艺路线下工序绑定的投料信息", description = "查询工艺路线下工序绑定的投料信息")
    @GetMapping("/getMatByCode")
    public R<List<MdmProcessProcedureMatEntity>> getMatByCode(@RequestParam("routeCode") String routeCode, @RequestParam("processCode") String processCode) {
        return R.ok(mdmProcessRouteService.getMatByCode(routeCode, processCode));
    }

    /**
     * 新增工艺路线下工序绑定的投料信息
     *
     * @param mdmProcessProcedureMatDTO 工艺路线-工序-投料
     * @return R
     */
    @Operation(summary = "新增工艺路线下工序绑定的投料信息", description = "新增工艺路线下工序绑定的投料信息")
    @PutMapping("/saveMat")
    public R<Boolean> saveMat(@RequestBody MdmProcessProcedureMatDTO mdmProcessProcedureMatDTO) {
        return R.ok(mdmProcessRouteService.saveMat(mdmProcessProcedureMatDTO));
    }

    /**
     * 查询工艺路线下工序的设置信息
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工序编码
     * @return R
     */
    @Operation(summary = "查询工艺路线下工序的设置信息", description = "查询工艺路线下工序的设置信息")
    @GetMapping("/getSetByCode")
    public R<MdmProcessProcedureSettingEntity> getSetByCode(@RequestParam("routeCode") String routeCode, @RequestParam("processCode") String processCode) {
        return R.ok(mdmProcessRouteService.getSetByCode(routeCode, processCode));
    }

    /**
     * 修改工艺路线下工序的设置信息
     *
     * @param mdmProcessProcedureSetting 工艺路线-工序设置
     * @return R
     */
    @Operation(summary = "修改工艺路线下工序的设置信息", description = "修改工艺路线下工序的设置信息")
    @PutMapping("/saveOrUpdateSet")
    public R<Boolean> saveOrUpdateSet(@RequestBody MdmProcessProcedureSettingEntity mdmProcessProcedureSetting) {
        return R.ok(mdmProcessRouteService.saveOrUpdateSet(mdmProcessProcedureSetting));
    }

}
