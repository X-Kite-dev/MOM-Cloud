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
import com.kitex.mom.mdm.vo.excel.MdmProductLineExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 产线表（查询接口，使用工厂建模表）
 *
 * @author kitex
 * @date 2025-05-14 10:56:01
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmProductLine")
@Tag(description = "mdmProductLine", name = "产线表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmProductLineController {

    private final MdmFactoryModelService factoryModelService;

    /**
     * 分页查询
     *
     * @param page           分页对象
     * @param mdmProductLine 产线表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<Page<MdmFactoryModelEntity>> getMdmProductLinePage(@ParameterObject SearchPage page, @ParameterObject MdmFactoryModelEntity mdmProductLine) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.LINE.getCode());

        if (StrUtil.isNotBlank(mdmProductLine.getCode())) {
            wrapper.like(MdmFactoryModelEntity::getCode, mdmProductLine.getCode());
        }
        if (StrUtil.isNotBlank(mdmProductLine.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, mdmProductLine.getName());
        }

        wrapper.orderByDesc(MdmFactoryModelEntity::getCreateTime);

        Page<MdmFactoryModelEntity> result = factoryModelService.page(page.toMpPage(), wrapper);
        return R.ok(result);
    }

    /**
     * 通过条件查询产线表
     *
     * @param mdmProductLine 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    public R<List<MdmFactoryModelEntity>> getDetails(@ParameterObject MdmFactoryModelEntity mdmProductLine) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.LINE.getCode());

        if (StrUtil.isNotBlank(mdmProductLine.getCode())) {
            wrapper.eq(MdmFactoryModelEntity::getCode, mdmProductLine.getCode());
        }
        if (StrUtil.isNotBlank(mdmProductLine.getName())) {
            wrapper.eq(MdmFactoryModelEntity::getName, mdmProductLine.getName());
        }

        List<MdmFactoryModelEntity> result = factoryModelService.list(wrapper);
        return R.ok(result);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmProductLineEntity 查询条件
     * @param ids                  导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProductLine_export')")
    public List<MdmProductLineExcelVO> exportExcel(MdmFactoryModelEntity mdmProductLineEntity, Long[] ids) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.LINE.getCode());

        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.in(MdmFactoryModelEntity::getId, ids);
        }

        return factoryModelService.list(wrapper)
                .stream()
                .map(factoryModel -> {
                    MdmProductLineExcelVO excelVO = new MdmProductLineExcelVO();
                    excelVO.setCode(factoryModel.getCode());
                    excelVO.setName(factoryModel.getName());
                    excelVO.setRemark(factoryModel.getDescription());

                    return excelVO;
                })
                .collect(Collectors.toList());
    }
}