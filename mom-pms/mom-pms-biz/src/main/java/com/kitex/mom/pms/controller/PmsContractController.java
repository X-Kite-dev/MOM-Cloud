package com.kitex.mom.pms.controller;


import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.pms.entity.PmsContractEntity;
import com.kitex.mom.pms.service.PmsContractService;
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
import java.util.stream.Collectors;

/**
 * 合同管理
 *
 * @author kitex
 * @date 2025-04-02 16:10:18
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/pmsContract")
@Tag(description = "pmsContract", name = "合同管理管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PmsContractController {

    private final PmsContractService pmsContractService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param pmsContract 合同管理
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_view')")
    public R getPmsContractPage(@ParameterObject SearchPage page, @ParameterObject PmsContractEntity pmsContract) {
        QueryWrapper<PmsContractEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .like(StrUtil.isNotBlank(pmsContract.getContractNo()), PmsContractEntity::getContractNo, pmsContract.getContractNo())
                .like(StrUtil.isNotBlank(pmsContract.getPmsOrderNo()), PmsContractEntity::getPmsOrderNo, pmsContract.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsContract.getContractName()), PmsContractEntity::getContractName, pmsContract.getContractName())
                .like(StrUtil.isNotBlank(pmsContract.getTitle()), PmsContractEntity::getTitle, pmsContract.getTitle())
                .eq(StrUtil.isNotBlank(pmsContract.getStatus()), PmsContractEntity::getStatus, pmsContract.getStatus())
                .orderByDesc(PmsContractEntity::getCreateTime);
        return R.ok(pmsContractService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各合同状态的数量
     *
     * @return
     */

    @Operation(summary = "查询各合同状态的数量", description = "查询各合同状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject PmsContractEntity pmsContract) {
        QueryWrapper<PmsContractEntity> wrapper = Wrappers.query();
        wrapper.lambda()
                .select(PmsContractEntity::getStatus)
                .like(StrUtil.isNotBlank(pmsContract.getContractNo()), PmsContractEntity::getContractNo, pmsContract.getContractNo())
                .like(StrUtil.isNotBlank(pmsContract.getPmsOrderNo()), PmsContractEntity::getPmsOrderNo, pmsContract.getPmsOrderNo())
                .like(StrUtil.isNotBlank(pmsContract.getContractName()), PmsContractEntity::getContractName, pmsContract.getContractName())
                .like(StrUtil.isNotBlank(pmsContract.getTitle()), PmsContractEntity::getTitle, pmsContract.getTitle());
        return R.ok(pmsContractService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(PmsContractEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过条件查询合同管理
     *
     * @param pmsContract 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_view')")
    public R getDetails(@ParameterObject PmsContractEntity pmsContract) {
        return R.ok(pmsContractService.listDeep(Wrappers.query(pmsContract)));
    }

    /**
     * 新增合同管理
     *
     * @param pmsContract 合同管理
     * @return R
     */
    @Operation(summary = "新增合同管理", description = "新增合同管理")
    @SysLog("新增合同管理")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_add')")
    public R save(@RequestBody PmsContractEntity pmsContract) {
        return R.ok(pmsContractService.saveDeep(pmsContract));
    }

    /**
     * 修改合同管理
     *
     * @param pmsContract 合同管理
     * @return R
     */
    @Operation(summary = "修改合同管理", description = "修改合同管理")
    @SysLog("修改合同管理")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_edit')")
    public R updateById(@RequestBody PmsContractEntity pmsContract) {
        return R.ok(pmsContractService.updateDeep(pmsContract));
    }

    /**
     * 通过id删除合同管理
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除合同管理", description = "通过id删除合同管理")
    @SysLog("通过id删除合同管理")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_del')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(pmsContractService.removeDeep(ids));
    }

    /**
     * 通过id删除合同管理子表数据
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除合同管理子表数据", description = "通过id删除合同管理子表数据")
    @SysLog("通过id删除合同管理子表数据")
    @DeleteMapping("/child")
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_del')")
    public R removeChild(@RequestBody Long[] ids) {
        return R.ok(pmsContractService.removeChild(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param pmsContract 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_export')")
    public List<PmsContractEntity> exportExcel(PmsContractEntity pmsContract, Long[] ids) {
        return pmsContractService.list(Wrappers.lambdaQuery(pmsContract).in(ArrayUtil.isNotEmpty(ids), PmsContractEntity::getId, ids));
    }

    /**
     * 导入excel 表
     *
     * @param pmsContractList 对象实体列表
     * @param bindingResult   错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('pms_pmsContract_export')")
    public R importExcel(@RequestExcel List<PmsContractEntity> pmsContractList, BindingResult bindingResult) {
        return R.ok(pmsContractService.saveBatch(pmsContractList));
    }
}