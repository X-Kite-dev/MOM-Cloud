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
import com.kitex.mom.mdm.vo.excel.MdmWorkCenterExcelVO;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作中心表（查询接口，使用工厂建模表）
 *
 * @author kitex
 * @date 2024-10-31 16:28:02
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmWorkCenter")
@Tag(description = "mdmWorkCenter", name = "工作中心管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmWorkCenterController {

    private final MdmFactoryModelService factoryModelService;

    /**
     * 分页查询
     *
     * @param page          分页对象
     * @param mdmWorkCenter 工作中心
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkCenter_view')")
    public R<Page<MdmFactoryModelEntity>> getMdmWorkCenterPage(SearchPage page, @ParameterObject MdmFactoryModelEntity mdmWorkCenter) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.STATION.getCode());

        if (StrUtil.isNotBlank(mdmWorkCenter.getCode())) {
            wrapper.like(MdmFactoryModelEntity::getCode, mdmWorkCenter.getCode());
        }
        if (StrUtil.isNotBlank(mdmWorkCenter.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, mdmWorkCenter.getName());
        }

        wrapper.orderByDesc(MdmFactoryModelEntity::getCreateTime);

        Page<MdmFactoryModelEntity> result = factoryModelService.page(page.toMpPage(), wrapper);
        return R.ok(result);
    }

    /**
     * 产线过滤-分页查询
     *
     * @param page          分页对象
     * @param mdmWorkCenter 工作中心
     * @return
     */
    @Operation(summary = "产线过滤-分页查询", description = "产线过滤-分页查询")
    @GetMapping("/pageByProductLine")
    public R<Page<MdmFactoryModelEntity>> getMdmWorkCenterPageByProductLine(SearchPage page, @ParameterObject MdmFactoryModelEntity mdmWorkCenter) {
        return getMdmWorkCenterPage(page, mdmWorkCenter);
    }

    /**
     * 查询工作中心列表
     *
     * @return 工作中心列表
     */
    @Operation(summary = "查询工作中心列表", description = "查询工作中心列表")
    @GetMapping("/list")
    public R<List<MdmFactoryModelEntity>> getList() {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.STATION.getCode())
                .eq(MdmFactoryModelEntity::getStatus, true);

        List<MdmFactoryModelEntity> result = factoryModelService.list(wrapper);
        return R.ok(result);
    }

    /**
     * 查询各工作中心状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各工作中心状态的数量", description = "查询各工作中心状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmFactoryModelEntity mdmWorkCenter) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.STATION.getCode());

        if (StrUtil.isNotBlank(mdmWorkCenter.getCode())) {
            wrapper.like(MdmFactoryModelEntity::getCode, mdmWorkCenter.getCode());
        }
        if (StrUtil.isNotBlank(mdmWorkCenter.getName())) {
            wrapper.like(MdmFactoryModelEntity::getName, mdmWorkCenter.getName());
        }

        Map<Boolean, Long> statusCount = factoryModelService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmFactoryModelEntity::getStatus, Collectors.counting()));


        return R.<Map<Boolean, Long>>ok(statusCount);
    }

    /**
     * 通过id查询工作中心
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkCenter_view')")
    public R<MdmFactoryModelEntity> getById(@PathVariable("id") Long id) {
        MdmFactoryModelEntity factoryModel = factoryModelService.getById(id);
        if (factoryModel == null || !FactoryModelTypeEnum.STATION.getCode().equals(factoryModel.getType())) {
            return R.failed("工作中心不存在");
        }
        return R.ok(factoryModel);
    }

    /**
     * 根据工作中心编码获取详情
     *
     * @param code 工作中心编码
     * @return R
     */
    @Operation(summary = "根据工作中心编码获取详情", description = "根据工作中心编码获取详情")
    @GetMapping("/details")
    public R<MdmFactoryModelEntity> details(String code) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.STATION.getCode())
                .eq(MdmFactoryModelEntity::getCode, code);

        MdmFactoryModelEntity result = factoryModelService.getOne(wrapper, false);
        return R.ok(result);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmWorkCenter 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmWorkCenter_export')")
    public List<MdmWorkCenterExcelVO> export(MdmFactoryModelEntity mdmWorkCenter, Long[] ids) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.STATION.getCode());

        if (ArrayUtil.isNotEmpty(ids)) {
            wrapper.in(MdmFactoryModelEntity::getId, ids);
        }

        return factoryModelService.list(wrapper)
                .stream()
                .map(factoryModel -> {
                    MdmWorkCenterExcelVO excelVO = new MdmWorkCenterExcelVO();
                    excelVO.setCode(factoryModel.getCode());
                    excelVO.setName(factoryModel.getName());
                    excelVO.setAvailableStatus(String.valueOf(factoryModel.getStatus()));

                    return excelVO;
                })
                .collect(Collectors.toList());
    }
}