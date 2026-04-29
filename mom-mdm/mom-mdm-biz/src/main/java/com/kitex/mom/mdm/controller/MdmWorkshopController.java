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
import com.kitex.mom.mdm.vo.MdmWorkShopTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmWorkshopExcelVO;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 车间表（查询接口，使用工厂建模表）
 *
 * @author kitex
 * @date 2024-10-30 16:20:01
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmWorkshop")
@Tag(description = "mdmWorkshop", name = "车间管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmWorkshopController {

    private final MdmFactoryModelService factoryModelService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmWorkshop 车间
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkshop_view')")
    public R<Page<MdmFactoryModelEntity>> getMdmWorkshopPage(SearchPage page, @ParameterObject MdmFactoryModelEntity mdmWorkshop) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.WORKSHOP.getCode());

        if (StrUtil.isNotEmpty(mdmWorkshop.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, mdmWorkshop.getName());
        }
        if (Objects.nonNull(mdmWorkshop.getStatus())) {
            wrapper.eq(MdmFactoryModelEntity::getStatus, mdmWorkshop.getStatus());
        }

        wrapper.orderByDesc(MdmFactoryModelEntity::getCreateTime);

        Page<MdmFactoryModelEntity> result = factoryModelService.page(page.toMpPage(), wrapper);
        return R.ok(result);
    }

    /**
     * 车间列表查询
     *
     * @return
     */
    @Operation(summary = "车间列表查询", description = "车间列表查询")
    @GetMapping("/list")
    public R<List<MdmFactoryModelEntity>> getList() {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.WORKSHOP.getCode())
                .eq(MdmFactoryModelEntity::getStatus, true);

        List<MdmFactoryModelEntity> result = factoryModelService.list(wrapper);
        return R.ok(result);
    }

    /**
     * 查询各车间状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各车间状态的数量", description = "查询各车间状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmFactoryModelEntity mdmWorkshop) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.WORKSHOP.getCode());

        if (StrUtil.isNotEmpty(mdmWorkshop.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, mdmWorkshop.getName());
        }

        Map<Boolean, Long> statusCount = factoryModelService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmFactoryModelEntity::getStatus, Collectors.counting()));
        return R.<Map<Boolean, Long>>ok(statusCount);
    }

    /**
     * 车间树查询
     *
     * @param mdmWorkshop 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "车间树查询", description = "车间树查询")
    @GetMapping("/tree")
    public R<List<MdmWorkShopTreeVO>> getWorkShopTree(@ParameterObject MdmFactoryModelEntity mdmWorkshop) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.WORKSHOP.getCode())
                .eq(MdmFactoryModelEntity::getStatus, Boolean.TRUE);

        if (StrUtil.isNotEmpty(mdmWorkshop.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, mdmWorkshop.getName());
        }
        if (StrUtil.isNotEmpty(mdmWorkshop.getCode())) {
            wrapper.like(MdmFactoryModelEntity::getCode, mdmWorkshop.getCode());
        }

        List<MdmFactoryModelEntity> factoryModels = factoryModelService.list(wrapper);
        List<MdmWorkShopTreeVO> treeVOs = factoryModels.stream()
                .map(this::convertToWorkshopTreeVO)
                .collect(Collectors.toList());

        return R.ok(treeVOs);
    }

    /**
     * 通过条件查询车间
     *
     * @param mdmWorkshop 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkshop_view')")
    public R<MdmFactoryModelEntity> getDetails(@ParameterObject MdmFactoryModelEntity mdmWorkshop) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.WORKSHOP.getCode());

        if (StrUtil.isNotEmpty(mdmWorkshop.getCode())) {
            wrapper.eq(MdmFactoryModelEntity::getCode, mdmWorkshop.getCode());
        }
        if (StrUtil.isNotEmpty(mdmWorkshop.getName())) {
            wrapper.eq(MdmFactoryModelEntity::getName, mdmWorkshop.getName());
        }

        MdmFactoryModelEntity result = factoryModelService.getOne(wrapper, false);
        return R.ok(result);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmWorkshopEntity 查询条件
     * @param ids               导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkshop_export')")
    public List<MdmWorkshopExcelVO> exportExcel(MdmFactoryModelEntity mdmWorkshopEntity, Long[] ids) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.WORKSHOP.getCode());

        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.in(MdmFactoryModelEntity::getId, ids);
        }

        return factoryModelService.list(wrapper)
                .stream()
                .map(factoryModel -> {
                    MdmWorkshopExcelVO excelVO = new MdmWorkshopExcelVO();
                    excelVO.setCode(factoryModel.getCode());
                    excelVO.setName(factoryModel.getName());
                    excelVO.setStatus(factoryModel.getStatus() != null && factoryModel.getStatus() ? "启用" : "禁用");
                    excelVO.setSuperintendent(factoryModel.getManager());
                    excelVO.setDescription(factoryModel.getDescription());

                    return excelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 转换为车间树VO
     */
    private MdmWorkShopTreeVO convertToWorkshopTreeVO(MdmFactoryModelEntity factoryModel) {
        MdmWorkShopTreeVO treeVO = new MdmWorkShopTreeVO();
        treeVO.setId(factoryModel.getId());
        treeVO.setCode(factoryModel.getCode());
        treeVO.setName(factoryModel.getName());
        return treeVO;
    }
}