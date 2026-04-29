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
import com.kitex.mom.mdm.entity.MdmCustomerPartEntity;
import com.kitex.mom.mdm.service.MdmCustomerPartService;
import com.kitex.mom.mdm.vo.excel.MdmCustomerPartExcelVO;
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
 * 客户零件号
 *
 * @author kitex
 * @date 2024-10-30 14:03:10
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmCustomerPart")
@Tag(description = "mdmCustomerPart", name = "客户零件号管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmCustomerPartController {

    private final MdmCustomerPartService mdmCustomerPartService;

    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param mdmCustomerPart 客户零件号
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomerPart_view')")
    public R<Page<MdmCustomerPartEntity>> getMdmCustomerPartPage(SearchPage page, @ParameterObject MdmCustomerPartEntity mdmCustomerPart) {
        QueryWrapper<MdmCustomerPartEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(mdmCustomerPart.getCustomerCode()), MdmCustomerPartEntity::getCustomerCode, mdmCustomerPart.getCustomerCode())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getCustomerName()), MdmCustomerPartEntity::getCustomerName, mdmCustomerPart.getCustomerName())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getPartNumber()), MdmCustomerPartEntity::getPartNumber, mdmCustomerPart.getPartNumber())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getMaterialCode()), MdmCustomerPartEntity::getMaterialCode, mdmCustomerPart.getMaterialCode())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getMaterialName()), MdmCustomerPartEntity::getMaterialName, mdmCustomerPart.getMaterialName())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getMaterialSpec()), MdmCustomerPartEntity::getMaterialSpec, mdmCustomerPart.getMaterialSpec())
                .eq(Objects.nonNull(mdmCustomerPart.getStatus()), MdmCustomerPartEntity::getStatus, mdmCustomerPart.getStatus())
                .orderByDesc(MdmCustomerPartEntity::getCreateTime);
        return R.ok(mdmCustomerPartService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过id查询客户零件号
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomerPart_view')")
    public R<MdmCustomerPartEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmCustomerPartService.getById(id));
    }

    /**
     * 查询各客户零件号状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各客户零件号状态的数量", description = "查询各客户零件号状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmCustomerPartEntity mdmCustomerPart) {
        QueryWrapper<MdmCustomerPartEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmCustomerPartEntity::getStatus)
                .like(StrUtil.isNotBlank(mdmCustomerPart.getCustomerCode()), MdmCustomerPartEntity::getCustomerCode,
                        mdmCustomerPart.getCustomerCode())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getCustomerName()), MdmCustomerPartEntity::getCustomerName,
                        mdmCustomerPart.getCustomerName())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getPartNumber()), MdmCustomerPartEntity::getPartNumber,
                        mdmCustomerPart.getPartNumber())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getMaterialSpec()), MdmCustomerPartEntity::getMaterialSpec,
                        mdmCustomerPart.getMaterialSpec())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getMaterialCode()), MdmCustomerPartEntity::getMaterialCode,
                        mdmCustomerPart.getMaterialCode())
                .like(StrUtil.isNotBlank(mdmCustomerPart.getMaterialName()), MdmCustomerPartEntity::getMaterialName,
                        mdmCustomerPart.getMaterialName());
        return R.ok(mdmCustomerPartService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmCustomerPartEntity::getStatus, Collectors.counting())));
    }

    /**
     * 查询客户列表信息
     *
     * @return R
     */
    @Operation(summary = "查询客户列表信息", description = "查询客户列表信息")
    @GetMapping("/list")
    public R<List<MdmCustomerPartEntity>> getList() {
        return R.ok(mdmCustomerPartService
                .list(new LambdaQueryWrapper<MdmCustomerPartEntity>().eq(MdmCustomerPartEntity::getStatus, Boolean.TRUE)));
    }


    /**
     * 根据客户编码 和 物料编码 查询客户零件号
     */
    @Operation(summary = "根据客户编码 和 物料编码 查询客户零件号", description = "根据客户编码 和 物料编码 查询客户零件号")
    @GetMapping("/getPartCodeByMatWithCustomerCode")
    public R<MdmCustomerPartEntity> getPartCodeByMatWithCustomerCode(@RequestParam("productCode") String productCode,
                                                                     @RequestParam("customerCode") String customerCode) {
        return R.ok(mdmCustomerPartService.getOne(new LambdaQueryWrapper<MdmCustomerPartEntity>()
                .eq(MdmCustomerPartEntity::getCustomerCode, customerCode)
                .eq(MdmCustomerPartEntity::getMaterialCode, productCode)
                .eq(MdmCustomerPartEntity::getStatus, Boolean.TRUE)
                .last("LIMIT 1")));
    }

    /**
     * 根据物料编码 查询客户零件号
     */
    @Operation(summary = "根据物料编码 查询客户零件号", description = "根据物料编码 查询客户零件号")
    @GetMapping("/getPartCodeByMatCode")
    public R<List<MdmCustomerPartEntity>> getPartCodeByMatCode(@RequestParam("materialCode") String materialCode) {
        return R.ok(mdmCustomerPartService.list(new LambdaQueryWrapper<MdmCustomerPartEntity>()
                .eq(MdmCustomerPartEntity::getMaterialCode, materialCode)
                .eq(MdmCustomerPartEntity::getStatus, Boolean.TRUE)));
    }


    /**
     * 根据零件号和客户编码获取详情
     *
     * @param partNumber   零件号
     * @param customerCode 客户编码
     * @return R
     */
    @Operation(summary = "根据零件号和客户编码获取详情", description = "根据零件号和客户编码获取详情")
    @GetMapping("/details")
    public R<MdmCustomerPartEntity> details(String partNumber, String customerCode) {
        return R.ok(mdmCustomerPartService.getOne(
                Wrappers.<MdmCustomerPartEntity>lambdaQuery().eq(MdmCustomerPartEntity::getPartNumber, partNumber)
                        .eq(MdmCustomerPartEntity::getCustomerCode, customerCode), false));
    }

    /**
     * 新增客户零件号
     *
     * @param mdmCustomerPart 客户零件号
     * @return R
     */
    @Operation(summary = "新增客户零件号", description = "新增客户零件号")
    @SysLog("新增客户零件号")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomerPart_add')")
    public R<Boolean> save(@RequestBody MdmCustomerPartEntity mdmCustomerPart) {
        return R.ok(mdmCustomerPartService.save(mdmCustomerPart));
    }

    /**
     * 修改客户零件号
     *
     * @param mdmCustomerPart 客户零件号
     * @return R
     */
    @Operation(summary = "修改客户零件号", description = "修改客户零件号")
    @SysLog("修改客户零件号")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomerPart_edit')")
    public R<Boolean> updateById(@RequestBody MdmCustomerPartEntity mdmCustomerPart) {
        return R.ok(mdmCustomerPartService.updateById(mdmCustomerPart));
    }

    /**
     * 通过id删除客户零件号
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除客户零件号", description = "通过id删除客户零件号")
    @SysLog("通过id删除客户零件号")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomerPart_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmCustomerPartService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   客户零件号列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomerPart_export')")
    public R importCustomerPart(@RequestExcel(headRowNumber = 2) List<MdmCustomerPartExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmCustomerPartService.importCustomerPart(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmCustomerPart 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomerPart_export')")
    public List<MdmCustomerPartExcelVO> export(MdmCustomerPartEntity mdmCustomerPart, Long[] ids) {
        return mdmCustomerPartService.export(mdmCustomerPart, ids);
    }

}