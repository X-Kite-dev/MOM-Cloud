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
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.service.MdmSupplierService;
import com.kitex.mom.mdm.vo.excel.MdmSupplierExcelVO;
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
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 供应商
 *
 * @author kitex
 * @date 2024-10-25 14:17:27
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmSupplier")
@Tag(description = "mdmSupplier", name = "供应商管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmSupplierController {

    private final MdmSupplierService mdmSupplierService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmSupplier 供应商
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_view')")
    public R<Page<MdmSupplierEntity>> getMdmSupplierPage(SearchPage page, @ParameterObject MdmSupplierEntity mdmSupplier) {
        QueryWrapper<MdmSupplierEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(mdmSupplier.getSupplierCode()), MdmSupplierEntity::getSupplierCode, mdmSupplier.getSupplierCode())
                .like(StrUtil.isNotBlank(mdmSupplier.getSupplierName()), MdmSupplierEntity::getSupplierName, mdmSupplier.getSupplierName())
                .eq(Objects.nonNull(mdmSupplier.getStatus()), MdmSupplierEntity::getStatus, mdmSupplier.getStatus())
                .eq(StrUtil.isNotBlank(mdmSupplier.getSupplierType()), MdmSupplierEntity::getSupplierType, mdmSupplier.getSupplierType())
                .eq(Objects.nonNull(mdmSupplier.getIsDeliver()), MdmSupplierEntity::getIsDeliver, mdmSupplier.getIsDeliver())
                .eq(StrUtil.isNotBlank(mdmSupplier.getCheckStatus()), MdmSupplierEntity::getCheckStatus, mdmSupplier.getCheckStatus())
                .orderByDesc(MdmSupplierEntity::getCreateTime);
        if (StrUtil.isNotBlank(mdmSupplier.getSupplierClassCode())) {
            List<String> codes = Arrays.stream(mdmSupplier.getSupplierClassCode().split(",")).toList();
            wrapper.lambda().in(MdmSupplierEntity::getSupplierClassCode, codes);
        }
        return R.ok(mdmSupplierService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询供应商信息
     *
     * @param query
     * @return
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_view')")
    public R<MdmSupplierEntity> getDetails(@ParameterObject MdmSupplierEntity query) {
        return R.ok(mdmSupplierService.getOne(Wrappers.query(query), false));
    }

    /**
     * 查询供应商列表
     */
    @Operation(summary = "查询供应商列表", description = "查询供应商列表")
    @GetMapping("/list")
    public R<List<MdmSupplierEntity>> getList(@ParameterObject MdmSupplierEntity query) {
        return R.ok(mdmSupplierService.list(Wrappers.query(query)));
    }

    /**
     * 查询供应商各启用状态的数量
     *
     * @return
     */
    @Operation(summary = "查询供应商各启用状态的数量", description = "查询供应商各启用状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmSupplierEntity mdmSupplier) {
        QueryWrapper<MdmSupplierEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmSupplierEntity::getStatus)
                .eq(StrUtil.isNotBlank(mdmSupplier.getCheckStatus()), MdmSupplierEntity::getCheckStatus, mdmSupplier.getCheckStatus())
                .like(StrUtil.isNotBlank(mdmSupplier.getSupplierCode()), MdmSupplierEntity::getSupplierCode, mdmSupplier.getSupplierCode())
                .like(StrUtil.isNotBlank(mdmSupplier.getSupplierName()), MdmSupplierEntity::getSupplierName, mdmSupplier.getSupplierName())
                .eq(StrUtil.isNotBlank(mdmSupplier.getSupplierType()), MdmSupplierEntity::getSupplierType, mdmSupplier.getSupplierType())
                .eq(Objects.nonNull(mdmSupplier.getIsDeliver()), MdmSupplierEntity::getIsDeliver, mdmSupplier.getIsDeliver())
                .orderByDesc(MdmSupplierEntity::getCreateTime);
        if (StrUtil.isNotBlank(mdmSupplier.getSupplierClassCode())) {
            List<String> codes = Arrays.stream(mdmSupplier.getSupplierClassCode().split(",")).toList();
            wrapper.lambda().in(MdmSupplierEntity::getSupplierClassCode, codes);
        }
        return R.ok(mdmSupplierService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmSupplierEntity::getStatus, Collectors.counting())));
    }

    /**
     * 查询供应商各审核状态的数量
     *
     * @return
     */
    @Operation(summary = "查询供应商各审核状态的数量", description = "查询供应商各审核状态的数量")
    @GetMapping("/getCheckCount")
    public R<Map<String, Long>> getCheckCount(@ParameterObject MdmSupplierEntity mdmSupplier) {
        QueryWrapper<MdmSupplierEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmSupplierEntity::getCheckStatus)
                .like(StrUtil.isNotBlank(mdmSupplier.getSupplierCode()), MdmSupplierEntity::getSupplierCode, mdmSupplier.getSupplierCode())
                .like(StrUtil.isNotBlank(mdmSupplier.getSupplierName()), MdmSupplierEntity::getSupplierName, mdmSupplier.getSupplierName())
                .eq(Objects.nonNull(mdmSupplier.getStatus()), MdmSupplierEntity::getStatus, mdmSupplier.getStatus())
                .eq(StrUtil.isNotBlank(mdmSupplier.getSupplierType()), MdmSupplierEntity::getSupplierType, mdmSupplier.getSupplierType())
                .eq(Objects.nonNull(mdmSupplier.getIsDeliver()), MdmSupplierEntity::getIsDeliver, mdmSupplier.getIsDeliver())
                .orderByDesc(MdmSupplierEntity::getCreateTime);
        if (StrUtil.isNotBlank(mdmSupplier.getSupplierClassCode())) {
            List<String> codes = Arrays.stream(mdmSupplier.getSupplierClassCode().split(",")).toList();
            wrapper.lambda().in(MdmSupplierEntity::getSupplierClassCode, codes);
        }
        return R.ok(mdmSupplierService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmSupplierEntity::getCheckStatus, Collectors.counting())));
    }

    /**
     * 新增供应商
     *
     * @param mdmSupplier 供应商
     * @return R
     */
    @Operation(summary = "新增供应商", description = "新增供应商")
    @SysLog("新增供应商")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_add')")
    public R<Long> save(@RequestBody MdmSupplierEntity mdmSupplier) {
        mdmSupplierService.save(mdmSupplier);
        return R.ok(mdmSupplier.getId());
    }

    /**
     * 修改供应商
     *
     * @param mdmSupplier 供应商
     * @return R
     */
    @Operation(summary = "修改供应商", description = "修改供应商")
    @SysLog("修改供应商")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_edit')")
    public R<Boolean> updateById(@RequestBody MdmSupplierEntity mdmSupplier) {
        return R.ok(mdmSupplierService.updateById(mdmSupplier));
    }

    /**
     * 审核通过/驳回
     *
     * @param mdmSupplierEntity 供应商表
     * @return R
     */
    @Operation(summary = "审核通过/驳回", description = "审核通过/驳回")
    @PutMapping("/check")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_edit')")
    public R<Boolean> check(@RequestBody MdmSupplierEntity mdmSupplierEntity) {
        return R.ok(mdmSupplierService.check(mdmSupplierEntity));
    }

    /**
     * 通过id删除供应商
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除供应商", description = "通过id删除供应商")
    @SysLog("通过id删除供应商")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmSupplierService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   供应商列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_export')")
    public R importSupplier(@RequestExcel(headRowNumber = 2) List<MdmSupplierExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmSupplierService.importSupplier(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmSupplier 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmSupplier_export')")
    public List<MdmSupplierExcelVO> export(MdmSupplierEntity mdmSupplier, Long[] ids) {
        return mdmSupplierService.export(mdmSupplier, ids);
    }

}