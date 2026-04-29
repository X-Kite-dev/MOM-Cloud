package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.entity.MdmMatUnitConversionEntity;
import com.kitex.mom.mdm.service.MdmMatUnitConversionService;
import com.kitex.mom.mdm.vo.excel.MdmMatUnitConversionExcelVO;
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
 * 物料单位
 *
 * @author kitex
 * @date 2024-12-12 10:59:24
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmMatUnit")
@Tag(description = "mdmMatUnit", name = "物料单位管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmMatUnitConversionController {

    private final MdmMatUnitConversionService mdmMatUnitConversionService;

    /**
     * 分页查询
     *
     * @param page       分页对象
     * @param mdmMatUnit 物料单位
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMatUnit_view')")
    public R<Page<MdmMatUnitConversionEntity>> getMdmMatUnitPage(@ParameterObject SearchPage page, @ParameterObject MdmMatUnitConversionEntity mdmMatUnit) {
        QueryWrapper<MdmMatUnitConversionEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(mdmMatUnit.getMatCode()), MdmMatUnitConversionEntity::getMatCode, mdmMatUnit.getMatCode())
                .like(StrUtil.isNotBlank(mdmMatUnit.getMatName()), MdmMatUnitConversionEntity::getMatName, mdmMatUnit.getMatName())
                .like(StrUtil.isNotBlank(mdmMatUnit.getNonStandardUnit()), MdmMatUnitConversionEntity::getNonStandardUnit, mdmMatUnit.getNonStandardUnit())
                .like(StrUtil.isNotBlank(mdmMatUnit.getStandardUnit()), MdmMatUnitConversionEntity::getStandardUnit, mdmMatUnit.getStandardUnit())
                .like(StrUtil.isNotBlank(mdmMatUnit.getMatSpec()), MdmMatUnitConversionEntity::getMatSpec, mdmMatUnit.getMatSpec())
                .orderByDesc(MdmMatUnitConversionEntity::getCreateTime);
        return R.ok(mdmMatUnitConversionService.page(page.toMpPage(), wrapper));
    }

    /**
     * 通过条件查询物料单位
     *
     * @param mdmMatUnit 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    // @PreAuthorize("@pms.hasPermission('mdm_mdmMatUnit_view')")
    public R<List<MdmMatUnitConversionEntity>> getDetails(@ParameterObject MdmMatUnitConversionEntity mdmMatUnit) {
        return R.ok(mdmMatUnitConversionService.list(Wrappers.query(mdmMatUnit)));
    }

    /**
     * 新增物料单位
     *
     * @param mdmMatUnit 物料单位
     * @return R
     */
    @Operation(summary = "新增物料单位", description = "新增物料单位")
    @SysLog("新增物料单位")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMatUnit_add')")
    public R<Boolean> save(@RequestBody MdmMatUnitConversionEntity mdmMatUnit) {
        return R.ok(mdmMatUnitConversionService.save(mdmMatUnit));
    }

    /**
     * 修改物料单位
     *
     * @param mdmMatUnit 物料单位
     * @return R
     */
    @Operation(summary = "修改物料单位", description = "修改物料单位")
    @SysLog("修改物料单位")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMatUnit_edit')")
    public R<Boolean> updateById(@RequestBody MdmMatUnitConversionEntity mdmMatUnit) {
        return R.ok(mdmMatUnitConversionService.updateById(mdmMatUnit));
    }

    /**
     * 通过id删除物料单位
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除物料单位", description = "通过id删除物料单位")
    @SysLog("通过id删除物料单位")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMatUnit_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmMatUnitConversionService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmMatUnit 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMatUnit_export')")
    public List<MdmMatUnitConversionExcelVO> exportExcel(MdmMatUnitConversionEntity mdmMatUnit, Long[] ids) {
        return mdmMatUnitConversionService.exportExcel(mdmMatUnit, ids);
    }

    /**
     * 导入excel 表
     *
     * @param mdmMatUnitList 对象实体列表
     * @param bindingResult  错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMatUnit_export')")
    public R importExcel(@RequestExcel List<MdmMatUnitConversionEntity> mdmMatUnitList, BindingResult bindingResult) {
        return R.ok(mdmMatUnitConversionService.saveBatch(mdmMatUnitList));
    }

}