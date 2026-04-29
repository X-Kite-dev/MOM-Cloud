package com.kitex.mom.mdm.controller;

import cn.hutool.core.util.ObjUtil;
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
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.service.MdmCustomerService;
import com.kitex.mom.mdm.vo.excel.MdmCustomerExcelVO;
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
 * 客户表
 *
 * @author kitex
 * @date 2024-10-27 16:22:18
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmCustomer")
@Tag(description = "mdmCustomer", name = "客户表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmCustomerController {

    private final MdmCustomerService mdmCustomerService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmCustomer 客户表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomer_view')")
    public R<Page<MdmCustomerEntity>> getMdmCustomerPage(SearchPage page, @ParameterObject MdmCustomerEntity mdmCustomer) {
        QueryWrapper<MdmCustomerEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmCustomer.getCustomerCode()), MdmCustomerEntity::getCustomerCode,
                        mdmCustomer.getCustomerCode())
                .like(StrUtil.isNotBlank(mdmCustomer.getCustomerName()), MdmCustomerEntity::getCustomerName,
                        mdmCustomer.getCustomerName())
                .eq(ObjUtil.isNotEmpty(mdmCustomer.getStatus()), MdmCustomerEntity::getStatus, mdmCustomer.getStatus())
                .orderByDesc(MdmCustomerEntity::getCreateTime);
        return R.ok(mdmCustomerService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询客户列表信息
     *
     * @return R
     */
    @Operation(summary = "查询客户列表信息", description = "查询客户列表信息")
    @GetMapping("/list")
    public R<List<MdmCustomerEntity>> getList() {
        return R.ok(mdmCustomerService.listDeep(new LambdaQueryWrapper<MdmCustomerEntity>().eq(MdmCustomerEntity::getStatus, Boolean.TRUE)));
    }

    /**
     * 查询客户列表信息
     *
     * @return R
     */
    @Inner
    @Operation(summary = "查询客户列表信息", description = "查询客户列表信息")
    @GetMapping("/oms/list")
    public R<List<MdmCustomerEntity>> getListByOms() {
        return R.ok(mdmCustomerService.listDeep(new LambdaQueryWrapper<MdmCustomerEntity>().eq(MdmCustomerEntity::getStatus, Boolean.TRUE)));
    }

    /**
     * 查询各客户状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各客户状态的数量", description = "查询各客户状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmCustomerEntity mdmCustomer) {
        QueryWrapper<MdmCustomerEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmCustomerEntity::getStatus)
                .like(StrUtil.isNotBlank(mdmCustomer.getCustomerCode()), MdmCustomerEntity::getCustomerCode,
                        mdmCustomer.getCustomerCode())
                .like(StrUtil.isNotBlank(mdmCustomer.getCustomerName()), MdmCustomerEntity::getCustomerName,
                        mdmCustomer.getCustomerName());
        return R.ok(mdmCustomerService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmCustomerEntity::getStatus, Collectors.counting())));
    }

    /**
     * 新增客户表
     *
     * @param mdmCustomer 客户表
     * @return R
     */
    @Operation(summary = "新增客户表", description = "新增客户表")
    @SysLog("新增客户表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomer_add')")
    public R<Boolean> save(@RequestBody MdmCustomerEntity mdmCustomer) {
        return R.ok(mdmCustomerService.saveDeep(mdmCustomer));
    }

    /**
     * 根据条件查询客户信息
     *
     * @param query 查询条件
     * @return R
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomer_view')")
    public R<MdmCustomerEntity> getDetails(@ParameterObject MdmCustomerEntity query) {
        return R.ok(mdmCustomerService.getOneDeep(Wrappers.query(query)));
    }

    /**
     * 修改客户表
     *
     * @param mdmCustomer 客户表
     * @return R
     */
    @Operation(summary = "修改客户表", description = "修改客户表")
    @SysLog("修改客户表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomer_edit')")
    public R<Boolean> updateById(@RequestBody MdmCustomerEntity mdmCustomer) {
        return R.ok(mdmCustomerService.updateDeep(mdmCustomer));
    }

    /**
     * 通过id删除客户表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除客户表", description = "通过id删除客户表")
    @SysLog("通过id删除客户表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomer_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmCustomerService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmCustomer 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomer_export')")
    public List<MdmCustomerExcelVO> export(MdmCustomerEntity mdmCustomer, Long[] ids) {
        return mdmCustomerService.export(mdmCustomer, ids);
    }

    /**
     * 导入excel 表
     *
     * @param mdmCustomerList 对象实体列表
     * @param bindingResult   错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCustomer_export')")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<MdmCustomerExcelVO> mdmCustomerList, BindingResult bindingResult) {
        return mdmCustomerService.importExcel(mdmCustomerList, bindingResult);
    }

}