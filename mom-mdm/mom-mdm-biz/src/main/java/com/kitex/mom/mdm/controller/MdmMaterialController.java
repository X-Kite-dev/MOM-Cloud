package com.kitex.mom.mdm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.dto.MdmMaterialWarningConfigDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmSchedulingFamilyEntity;
import com.kitex.mom.mdm.enums.MatTypeEnum;
import com.kitex.mom.mdm.service.MdmMaterialIdentifierTemplateValidator;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.service.MdmSchedulingFamilyService;
import com.kitex.mom.mdm.utils.DynamicRefreshUtils;
import com.kitex.mom.mdm.vo.excel.MdmMaterialExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 物料表
 *
 * @author kitex
 * @date 2024-10-17 10:30:41
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmMaterial")
@Tag(description = "mdmMaterial", name = "物料表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmMaterialController {

    private final MdmMaterialService mdmMaterialService;
    private final MdmSchedulingFamilyService schedulingFamilyService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmMaterial 物料表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<Page<MdmMaterialEntity>> getMdmMaterialPage(SearchPage page,
                                                         @ParameterObject MdmMaterialEntity mdmMaterial) {
        QueryWrapper<MdmMaterialEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(mdmMaterial.getMaterialName()), MdmMaterialEntity::getMaterialName,
                        mdmMaterial.getMaterialName())
                .like(StrUtil.isNotBlank(mdmMaterial.getMaterialCode()), MdmMaterialEntity::getMaterialCode,
                        mdmMaterial.getMaterialCode())
                .eq(ObjUtil.isNotEmpty(mdmMaterial.getStatus()), MdmMaterialEntity::getStatus, mdmMaterial.getStatus())
                .eq(StrUtil.isNotBlank(mdmMaterial.getBasicUnit()), MdmMaterialEntity::getBasicUnit,
                        mdmMaterial.getBasicUnit())
                .eq(StrUtil.isNotBlank(mdmMaterial.getPurchaseUnit()), MdmMaterialEntity::getPurchaseUnit,
                        mdmMaterial.getPurchaseUnit())
                .like(StrUtil.isNotBlank(mdmMaterial.getSpecificationModel()), MdmMaterialEntity::getSpecificationModel,
                        mdmMaterial.getSpecificationModel())
                .orderByDesc(MdmMaterialEntity::getCreateTime);
        if (StrUtil.isNotBlank(mdmMaterial.getGroupCode())) {
            List<String> groupCodes = Arrays.stream(mdmMaterial.getGroupCode().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getGroupCode, groupCodes);
        }
        if (StrUtil.isNotBlank(mdmMaterial.getMaterialType())) {
            List<String> materialTypes = Arrays.stream(mdmMaterial.getMaterialType().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getMaterialType, materialTypes);
        }
        if (StrUtil.isNotBlank(mdmMaterial.getMaterialAttribute())) {
            List<String> materialAttributes = Arrays.stream(mdmMaterial.getMaterialAttribute().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getMaterialAttribute, materialAttributes);
        }
        return R.ok(mdmMaterialService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询-产品/半成品
     *
     * @param page        分页对象
     * @param mdmMaterial 物料表
     * @return
     */
    @Operation(summary = "分页查询产品/半成品", description = "分页查询产品/半成品")
    @GetMapping("/pageProduct")
    public R<Page<MdmMaterialEntity>> getMdmMaterialProductPage(SearchPage page,
                                                                @ParameterObject MdmMaterialEntity mdmMaterial) {
        QueryWrapper<MdmMaterialEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(mdmMaterial.getMaterialName()), MdmMaterialEntity::getMaterialName,
                        mdmMaterial.getMaterialName())
                .like(StrUtil.isNotBlank(mdmMaterial.getMaterialCode()), MdmMaterialEntity::getMaterialCode,
                        mdmMaterial.getMaterialCode())
                .eq(ObjUtil.isNotEmpty(mdmMaterial.getStatus()), MdmMaterialEntity::getStatus, mdmMaterial.getStatus())
                .eq(StrUtil.isNotBlank(mdmMaterial.getBasicUnit()), MdmMaterialEntity::getBasicUnit,
                        mdmMaterial.getBasicUnit())
                .eq(StrUtil.isNotBlank(mdmMaterial.getPurchaseUnit()), MdmMaterialEntity::getPurchaseUnit,
                        mdmMaterial.getPurchaseUnit())
                .like(StrUtil.isNotBlank(mdmMaterial.getSpecificationModel()), MdmMaterialEntity::getSpecificationModel,
                        mdmMaterial.getSpecificationModel())
                .orderByAsc(MdmMaterialEntity::getMaterialType);
        if (StrUtil.isNotBlank(mdmMaterial.getGroupCode())) {
            List<String> groupCodes = Arrays.stream(mdmMaterial.getGroupCode().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getGroupCode, groupCodes);
        }
        // 获取物料型态，只查询产品或半成品
        List<String> materialTypes = Arrays.asList(MatTypeEnum.SEMI.getCode(), MatTypeEnum.PRODUCT.getCode());
        wrapper.lambda().in(MdmMaterialEntity::getMaterialType, materialTypes);
        if (StrUtil.isNotBlank(mdmMaterial.getMaterialAttribute())) {
            List<String> materialAttributes = Arrays.stream(mdmMaterial.getMaterialAttribute().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getMaterialAttribute, materialAttributes);
        }
        return R.ok(mdmMaterialService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询物料列表
     *
     * @return R
     */
    @Operation(summary = "查询物料列表", description = "查询物料列表")
    @GetMapping("/list")
    public R<List<MdmMaterialEntity>> getList() {
        return R.ok(mdmMaterialService
                .list(new LambdaQueryWrapper<MdmMaterialEntity>().eq(MdmMaterialEntity::getStatus, Boolean.TRUE)));
    }

    /**
     * 查询物料列表
     *
     * @return R
     */
    @Operation(summary = "查询物料列表", description = "查询物料列表")
    @Inner(false)
    @GetMapping("/allList")
    public R<List<MdmMaterialEntity>> getAllList() {
        return R.ok(mdmMaterialService.list());
    }

    /**
     * 根据物料编码列表查询物料列表
     *
     * @return R
     */
    @Operation(summary = "根据物料编码列表查询物料列表", description = "根据物料编码列表查询物料列表")
    @GetMapping("/listByCodes")
    public R<List<MdmMaterialEntity>> getListByCodes(@RequestParam(value = "codes") List<String> codes) {
        return R.ok(mdmMaterialService
                .list(new LambdaQueryWrapper<MdmMaterialEntity>().in(MdmMaterialEntity::getMaterialCode, codes)));
    }

    /**
     * 查询各物料状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各物料状态的数量", description = "查询各物料状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmMaterialEntity mdmMaterial) {
        QueryWrapper<MdmMaterialEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmMaterialEntity::getStatus)
                .like(StrUtil.isNotBlank(mdmMaterial.getMaterialName()), MdmMaterialEntity::getMaterialName,
                        mdmMaterial.getMaterialName())
                .like(StrUtil.isNotBlank(mdmMaterial.getMaterialCode()), MdmMaterialEntity::getMaterialCode,
                        mdmMaterial.getMaterialCode())
                .eq(StrUtil.isNotBlank(mdmMaterial.getBasicUnit()), MdmMaterialEntity::getBasicUnit,
                        mdmMaterial.getBasicUnit())
                .eq(StrUtil.isNotBlank(mdmMaterial.getPurchaseUnit()), MdmMaterialEntity::getPurchaseUnit,
                        mdmMaterial.getPurchaseUnit())
                .like(StrUtil.isNotBlank(mdmMaterial.getSpecificationModel()), MdmMaterialEntity::getSpecificationModel,
                        mdmMaterial.getSpecificationModel())
                .orderByDesc(MdmMaterialEntity::getCreateTime);
        if (StrUtil.isNotBlank(mdmMaterial.getGroupCode())) {
            List<String> groupCodes = Arrays.stream(mdmMaterial.getGroupCode().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getGroupCode, groupCodes);
        }
        if (StrUtil.isNotBlank(mdmMaterial.getMaterialType())) {
            List<String> materialTypes = Arrays.stream(mdmMaterial.getMaterialType().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getMaterialType, materialTypes);
        }
        if (StrUtil.isNotBlank(mdmMaterial.getMaterialAttribute())) {
            List<String> materialAttributes = Arrays.stream(mdmMaterial.getMaterialAttribute().split(",")).toList();
            wrapper.lambda().in(MdmMaterialEntity::getMaterialAttribute, materialAttributes);
        }
        return R.ok(mdmMaterialService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmMaterialEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过id查询物料表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    public R<MdmMaterialEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmMaterialService.getById(id));
    }

    /**
     * 根据物料编码获取详情
     *
     * @param materialCode 物料编码
     * @return R
     */
    @Operation(summary = "根据物料编码获取详情", description = "根据物料编码获取详情")
    @GetMapping("/details")
    public R<MdmMaterialEntity> details(String materialCode) {
        return R.ok(mdmMaterialService
                .getOne(Wrappers.<MdmMaterialEntity>lambdaQuery().eq(MdmMaterialEntity::getMaterialCode,
                        materialCode)));
    }

    /**
     * 内部系统任务入口；scope来自调度配置，不接受普通外部请求。
     */
    @Inner
    @GetMapping("/internal/warning-configurations")
    public R<List<MdmMaterialWarningConfigDTO>> warningConfigurations(@RequestParam Long tenantId,
                                                                      @RequestParam String factoryCode) {
        return R.ok(mdmMaterialService.warningConfigurations(tenantId, factoryCode));
    }

    /**
     * 内部标记不替代实名与实际scope；原更新时间文本保留精度。
     */
    @Inner
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "观察生产物料基本计量", description = "仅实际租户工厂唯一启用物料，不提供匿名或跨厂旁路")
    @GetMapping("/internal/production-measure")
    public R<MdmMaterialMeasureDTO> productionMeasure(@RequestParam String materialCode) {
        return R.ok(mdmMaterialService.productionMeasure(materialCode));
    }

    /**
     * 根据物料编码获取详情
     *
     * @param materialCode 物料编码
     * @return R
     */
    @Operation(summary = "根据物料编码获取详情", description = "根据物料编码获取详情")
    @Inner(false)
    @GetMapping("/detailsByNoToken")
    public R<MdmMaterialEntity> detailsByNoToken(String materialCode) {
        return R.ok(mdmMaterialService
                .getOne(Wrappers.<MdmMaterialEntity>lambdaQuery().eq(MdmMaterialEntity::getMaterialCode,
                        materialCode)));
    }

    /**
     * 物料打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Operation(summary = "物料打印", description = "物料打印")
    @GetMapping("/print")
    public R<String> print(@ParameterObject PrintReq printReq) {
        return R.ok(mdmMaterialService.print(printReq));
    }

    /**
     * 新增物料表
     *
     * @param mdmMaterial 物料表
     * @return R
     */
    @Operation(summary = "新增物料表", description = "新增物料表")
    @SysLog("新增物料表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterial_add')")
    public R<Boolean> save(@RequestBody MdmMaterialEntity mdmMaterial) {
        MdmMaterialIdentifierTemplateValidator.validate(mdmMaterial);
        resolveSchedulingFamily(mdmMaterial);
        mdmMaterialService.save(mdmMaterial);
        DynamicRefreshUtils.refreshMdm(mdmMaterial);
        return R.ok();
    }

    /**
     * 修改物料表
     *
     * @param mdmMaterial 物料表
     * @return R
     */
    @Operation(summary = "修改物料表", description = "修改物料表")
    @SysLog("修改物料表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterial_edit')")
    public R<Boolean> updateById(@RequestBody MdmMaterialEntity mdmMaterial) {
        MdmMaterialIdentifierTemplateValidator.validate(mdmMaterial);
        resolveSchedulingFamily(mdmMaterial);
        mdmMaterialService.updateById(mdmMaterial);
        DynamicRefreshUtils.refreshMdm(mdmMaterial);
        return R.ok();
    }

    private void resolveSchedulingFamily(MdmMaterialEntity material) {
        if (StrUtil.isBlank(material.getSchedulingFamilyCode())) {
            material.setSchedulingFamilyCode(null);
            material.setSchedulingFamilyName(null);
            return;
        }
        MdmSchedulingFamilyEntity family = schedulingFamilyService.getOne(
                new LambdaQueryWrapper<MdmSchedulingFamilyEntity>()
                        .eq(MdmSchedulingFamilyEntity::getFamilyCode, material.getSchedulingFamilyCode().trim())
                        .eq(MdmSchedulingFamilyEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                        .eq(MdmSchedulingFamilyEntity::getEnabled, true),
                false);
        if (family == null) {
            throw new com.kitex.common.core.exception.CheckedException("APS排产产品族不存在或未启用");
        }
        material.setSchedulingFamilyCode(family.getFamilyCode());
        material.setSchedulingFamilyName(family.getFamilyName());
    }

    /**
     * 通过id删除物料表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除物料表", description = "通过id删除物料表")
    @SysLog("通过id删除物料表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterial_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmMaterialService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterial_export')")
    public R importMaterial(@RequestExcel(headRowNumber = 2) List<MdmMaterialExcelVO> excelVOList,
                            BindingResult bindingResult) {
        return mdmMaterialService.importMaterial(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmMaterial 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmMaterial_export')")
    public List<MdmMaterialExcelVO> export(MdmMaterialEntity mdmMaterial, Long[] ids) {
        return mdmMaterialService.export(mdmMaterial, ids);
    }

}
