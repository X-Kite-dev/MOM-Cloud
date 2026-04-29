package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.dto.MdmBomDTO;
import com.kitex.mom.mdm.entity.MdmBomChildEntity;
import com.kitex.mom.mdm.entity.MdmBomEntity;
import com.kitex.mom.mdm.service.MdmBomService;
import com.kitex.mom.mdm.vo.MdmBomTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmBomExcelVO;
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
 * BOM
 *
 * @author kitex
 * @date 2024-10-30 14:45:14
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmBom")
@Tag(description = "mdmBom", name = "BOM管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmBomController {

    private final MdmBomService mdmBomService;

    /**
     * bom树查询
     *
     * @param mdmBom BOM
     * @return
     */
    @Operation(summary = "bom树查询", description = "bom树查询")
    @GetMapping("/tree")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_view')")
    public R<List<MdmBomTreeVO>> getMdmBomTree(@ParameterObject MdmBomEntity mdmBom) {
        QueryWrapper<MdmBomEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().and(StrUtil.isNotEmpty(mdmBom.getProductName()), wrapper -> wrapper
                .like(MdmBomEntity::getProductCode, mdmBom.getProductName())
                .or()
                .like(MdmBomEntity::getProductName, mdmBom.getProductName())
                .or()
                .like(MdmBomEntity::getProductSpec, mdmBom.getProductName()));
        return R.ok(mdmBomService.tree(queryWrapper));
    }

    /**
     * 分页查询
     *
     * @param page   分页对象
     * @param mdmBom BOM
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_view')")
    public R<Page<MdmBomEntity>> getMdmBomPage(SearchPage page, @ParameterObject MdmBomEntity mdmBom) {
        QueryWrapper<MdmBomEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmBom.getProductCode()), MdmBomEntity::getProductCode,
                mdmBom.getProductCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmBom.getProductName()), MdmBomEntity::getProductName,
                mdmBom.getProductName());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmBom.getVersion()), MdmBomEntity::getVersion, mdmBom.getVersion());
        wrapper.lambda().eq(Objects.nonNull(mdmBom.getEnabled()), MdmBomEntity::getEnabled, mdmBom.getEnabled());
        wrapper.lambda().orderByDesc(MdmBomEntity::getCreateTime);
        return R.ok(mdmBomService.page(page.toMpPage(), wrapper));
    }

    /**
     * 获取BOM list
     *
     * @param mdmBom BOM
     * @return R
     */
    @Operation(summary = "获取BOM list", description = "获取BOM list")
    @GetMapping("/list")
    public R<List<MdmBomEntity>> getMdmBomList(@ParameterObject MdmBomEntity mdmBom) {
        return R.ok(mdmBomService.list(Wrappers.lambdaQuery(mdmBom)));
    }

    /**
     * 查询各bom状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各bom状态的数量", description = "查询各bom状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmBomEntity mdmBom) {
        QueryWrapper<MdmBomEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmBomEntity::getEnabled)
                .like(StrUtil.isNotBlank(mdmBom.getProductCode()), MdmBomEntity::getProductCode, mdmBom.getProductCode())
                .like(StrUtil.isNotBlank(mdmBom.getProductName()), MdmBomEntity::getProductName, mdmBom.getProductName())
                .like(StrUtil.isNotBlank(mdmBom.getVersion()), MdmBomEntity::getVersion, mdmBom.getVersion());
        return R.ok(mdmBomService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmBomEntity::getEnabled, Collectors.counting())));
    }

    /**
     * 通过条件查询BOM
     *
     * @param mdmBom 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_view')")
    public R<MdmBomDTO> getDetails(@ParameterObject MdmBomEntity mdmBom) {
        return R.ok(mdmBomService.getBomDeep(mdmBom));
    }

    /**
     * 新增BOM
     *
     * @param mdmBomDTO BOM
     * @return R
     */
    @Operation(summary = "新增BOM", description = "新增BOM")
    @SysLog("新增BOM")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_add')")
    public R<Boolean> save(@RequestBody MdmBomDTO mdmBomDTO) {
        return R.ok(mdmBomService.saveDeep(mdmBomDTO));
    }

    /**
     * 修改BOM
     *
     * @param mdmBomDTO BOM
     * @return R
     */
    @Operation(summary = "修改BOM", description = "修改BOM")
    @SysLog("修改BOM")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_edit')")
    public R<Boolean> updateById(@RequestBody MdmBomDTO mdmBomDTO) {
        return R.ok(mdmBomService.updateDeep(mdmBomDTO));
    }

    /**
     * BOM复制
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "BOM复制", description = "BOM复制")
    @SysLog("BOM复制")
    @PostMapping("/bomCopy")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_add')")
    public R<Boolean> bomCopy(Long id) {
        return R.ok(mdmBomService.bomCopy(id));
    }

    /**
     * 根据id设置默认bom
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "根据id设置默认bom", description = "根据id设置默认bom")
    @SysLog("根据id设置默认bom")
    @PutMapping("/default")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_edit')")
    public R<Boolean> bomDefault(Long id) {
        return R.ok(mdmBomService.bomDefault(id));
    }

    /**
     * 通过id删除BOM
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除BOM", description = "通过id删除BOM")
    @SysLog("通过id删除BOM")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmBomService.removeDeep(ids));
    }

    /**
     * 通过id删除子物料
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除子物料", description = "通过id删除子物料")
    @SysLog("通过id删除子物料")
    @DeleteMapping("/child")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_del')")
    public R<Boolean> removeChild(@RequestBody Long[] ids) {
        return R.ok(mdmBomService.removeChild(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmBom 查询条件
     * @param ids    导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_export')")
    public List<MdmBomExcelVO> exportExcel(MdmBomEntity mdmBom, Long[] ids) {
        return mdmBomService.exportExcel(mdmBom, ids);
    }

    /**
     * 导入excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmBom_export')")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<MdmBomExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmBomService.importExcel(excelVOList, bindingResult);
    }

    /**
     * 根据产品编码获取该产品所有子物料bom信息
     *
     * @param productCode 产品编码
     * @param version     版本
     * @return 子物料集合, 按层次区分
     */
    @GetMapping("/childMaterials")
    public R<List<MdmBomChildDTO>> getChildMaterials(@RequestParam("productCode") String productCode,
                                                     @RequestParam("bomId") Long bomId,
                                                     @RequestParam(value = "version", required = false) String version) {
        return R.ok(mdmBomService.getChildMaterials(productCode, bomId, version));
    }

    /**
     * 根据产品编码列表获取该产品所有子物料bom信息包括自己
     *
     * @param productCodes 产品编码列表
     * @return 子物料集合, 按层次区分
     */
    @GetMapping("/allMaterials")
    public R<Map<String, MdmBomChildDTO>> getAllChildMaterials(@RequestParam("productCodes") List<String> productCodes) {
        return R.ok(mdmBomService.getAllChildMaterials(productCodes));
    }

    /**
     * 根据产品编码获取该产品子物料bom信息(一层)
     *
     * @param productCode 产品编码
     */
    @GetMapping("/getBomChild")
    public R<List<MdmBomChildEntity>> getBomChild(@RequestParam("productCode") String productCode,
                                                  @RequestParam(value = "bomVersion", required = false) String bomVersion) {
        return R.ok(mdmBomService.getBomChild(productCode, bomVersion));
    }

}
