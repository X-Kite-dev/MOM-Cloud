package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.vo.excel.MdmFactoryBuildingExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 厂房表（查询接口，使用工厂建模表）
 *
 * @author kitex
 * @date 2025-05-14 10:53:46
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmFactoryBuilding")
@Tag(description = "mdmFactoryBuilding", name = "厂房表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmFactoryBuildingController {

    private final MdmFactoryModelService factoryModelService;

    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param factoryBuilding 厂房查询条件
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmFactoryBuilding_view')")
    public R<Page<MdmFactoryModelEntity>> getMdmFactoryBuildingPage(@ParameterObject SearchPage page, @ParameterObject MdmFactoryModelEntity factoryBuilding) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode());

        if (StrUtil.isNotBlank(factoryBuilding.getCode())) {
            wrapper.like(MdmFactoryModelEntity::getCode, factoryBuilding.getCode());
        }
        if (StrUtil.isNotBlank(factoryBuilding.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, factoryBuilding.getName());
        }

        wrapper.orderByDesc(MdmFactoryModelEntity::getCreateTime);

        Page<MdmFactoryModelEntity> result = factoryModelService.page(page.toMpPage(), wrapper);
        return R.ok(result);
    }

    /**
     * 通过id查询厂房表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmFactoryBuilding_view')")
    public R<MdmFactoryModelEntity> getById(@PathVariable("id") Long id) {
        MdmFactoryModelEntity factoryModel = factoryModelService.getById(id);
        if (factoryModel == null || !FactoryModelTypeEnum.FACTORY.getCode().equals(factoryModel.getType())) {
            return R.failed("厂房不存在");
        }
        return R.ok(factoryModel);
    }

    /**
     * 查询厂房列表
     *
     * @param factoryBuilding 查询条件
     * @return R
     */
    @Operation(summary = "查询厂房列表", description = "查询厂房列表")
    @GetMapping("/list")
    public R<List<MdmFactoryModelEntity>> getList(@ParameterObject MdmFactoryModelEntity factoryBuilding) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode());

        if (StrUtil.isNotBlank(factoryBuilding.getCode())) {
            wrapper.like(MdmFactoryModelEntity::getCode, factoryBuilding.getCode());
        }
        if (StrUtil.isNotBlank(factoryBuilding.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, factoryBuilding.getName());
        }

        List<MdmFactoryModelEntity> result = factoryModelService.list(wrapper);
        return R.ok(result);
    }

    /**
     * 通过条件查询厂房
     *
     * @param factoryBuilding 查询条件
     * @return R
     */
    @Operation(summary = "通过条件查询厂房", description = "通过条件查询厂房")
    @GetMapping("/details")
    public R<MdmFactoryModelEntity> details(@ParameterObject MdmFactoryModelEntity factoryBuilding) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode());

        if (StrUtil.isNotBlank(factoryBuilding.getCode())) {
            wrapper.eq(MdmFactoryModelEntity::getCode, factoryBuilding.getCode());
        }
        if (StrUtil.isNotBlank(factoryBuilding.getName())) {
            wrapper.eq(MdmFactoryModelEntity::getName, factoryBuilding.getName());
        }

        MdmFactoryModelEntity result = factoryModelService.getOne(wrapper, false);
        return R.ok(result);
    }

    /**
     * 导出excel 表格
     *
     * @param factoryBuildingEntity 查询条件
     * @param ids                   导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmFactoryBuilding_export')")
    public List<MdmFactoryBuildingExcelVO> export(MdmFactoryModelEntity factoryBuildingEntity, Long[] ids) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode());

        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.in(MdmFactoryModelEntity::getId, ids);
        }

        return factoryModelService.list(wrapper)
                .stream()
                .map(factoryModel -> {
                    MdmFactoryBuildingExcelVO excelVO = new MdmFactoryBuildingExcelVO();
                    excelVO.setCode(factoryModel.getCode());
                    excelVO.setName(factoryModel.getName());
                    excelVO.setAddress(factoryModel.getAddress());
                    excelVO.setRemark(factoryModel.getDescription());
                    return excelVO;
                })
                .collect(Collectors.toList());
    }
}