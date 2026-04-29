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
import com.kitex.mom.mdm.entity.MdmEmployeeEntity;
import com.kitex.mom.mdm.enums.EmployeeStatusEnum;
import com.kitex.mom.mdm.service.MdmEmployeeService;
import com.kitex.mom.mdm.vo.excel.MdmEmployeeExcelVO;
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
 * 员工
 *
 * @author kitex
 * @date 2025-01-20 10:00:00
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmEmployee")
@Tag(description = "mdmEmployee", name = "员工管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmEmployeeController {

    private final MdmEmployeeService mdmEmployeeService;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmEmployee 员工
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    public R<Page<MdmEmployeeEntity>> getMdmEmployeePage(@ParameterObject SearchPage page, @ParameterObject MdmEmployeeEntity mdmEmployee) {
        QueryWrapper<MdmEmployeeEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmEmployee.getEmployeeCode()), MdmEmployeeEntity::getEmployeeCode, mdmEmployee.getEmployeeCode())
                .like(StrUtil.isNotBlank(mdmEmployee.getName()), MdmEmployeeEntity::getName, mdmEmployee.getName())
                .eq(StrUtil.isNotBlank(mdmEmployee.getGender()), MdmEmployeeEntity::getGender, mdmEmployee.getGender())
                .eq(StrUtil.isNotBlank(mdmEmployee.getStatus()), MdmEmployeeEntity::getStatus, mdmEmployee.getStatus())
                .like(StrUtil.isNotBlank(mdmEmployee.getDepartment()), MdmEmployeeEntity::getDepartment, mdmEmployee.getDepartment())
                .like(StrUtil.isNotBlank(mdmEmployee.getPosition()), MdmEmployeeEntity::getPosition, mdmEmployee.getPosition())
                .eq(Objects.nonNull(mdmEmployee.getSyncStatus()), MdmEmployeeEntity::getSyncStatus, mdmEmployee.getSyncStatus())
                .orderByDesc(MdmEmployeeEntity::getCreateTime);
        return R.ok(mdmEmployeeService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各员工状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各员工状态的数量", description = "查询各员工状态的数量")
    @GetMapping("/getCount")
    public R<Map<String, Long>> getCount(@ParameterObject MdmEmployeeEntity mdmEmployee) {
        QueryWrapper<MdmEmployeeEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmEmployee.getEmployeeCode()), MdmEmployeeEntity::getEmployeeCode, mdmEmployee.getEmployeeCode())
                .like(StrUtil.isNotBlank(mdmEmployee.getName()), MdmEmployeeEntity::getName, mdmEmployee.getName())
                .eq(StrUtil.isNotBlank(mdmEmployee.getGender()), MdmEmployeeEntity::getGender, mdmEmployee.getGender())
                .like(StrUtil.isNotBlank(mdmEmployee.getDepartment()), MdmEmployeeEntity::getDepartment, mdmEmployee.getDepartment())
                .like(StrUtil.isNotBlank(mdmEmployee.getPosition()), MdmEmployeeEntity::getPosition, mdmEmployee.getPosition())
                .eq(Objects.nonNull(mdmEmployee.getSyncStatus()), MdmEmployeeEntity::getSyncStatus, mdmEmployee.getSyncStatus())
                .orderByDesc(MdmEmployeeEntity::getCreateTime);
        return R.ok(mdmEmployeeService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmEmployeeEntity::getStatus, Collectors.counting())));
    }

    /**
     * 查询员工列表
     *
     * @return
     */
    @Operation(summary = "查询员工列表", description = "查询员工列表")
    @GetMapping("/list")
    public R<List<MdmEmployeeEntity>> list(@ParameterObject MdmEmployeeEntity mdmEmployee) {
        QueryWrapper<MdmEmployeeEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmEmployee.getEmployeeCode()), MdmEmployeeEntity::getEmployeeCode, mdmEmployee.getEmployeeCode())
                .like(StrUtil.isNotBlank(mdmEmployee.getName()), MdmEmployeeEntity::getName, mdmEmployee.getName())
                .eq(StrUtil.isNotBlank(mdmEmployee.getGender()), MdmEmployeeEntity::getGender, mdmEmployee.getGender())
                .eq(StrUtil.isNotBlank(mdmEmployee.getStatus()), MdmEmployeeEntity::getStatus, mdmEmployee.getStatus())
                .like(StrUtil.isNotBlank(mdmEmployee.getDepartment()), MdmEmployeeEntity::getDepartment, mdmEmployee.getDepartment())
                .like(StrUtil.isNotBlank(mdmEmployee.getPosition()), MdmEmployeeEntity::getPosition, mdmEmployee.getPosition())
                .eq(MdmEmployeeEntity::getStatus, "1")
                .orderByDesc(MdmEmployeeEntity::getUpdateTime);
        return R.ok(mdmEmployeeService.list(wrapper));
    }

    /**
     * 根据id查询员工信息
     *
     * @param id id
     * @return R  对象
     */
    @Operation(summary = "根据id查询员工信息", description = "根据id查询员工信息")
    @GetMapping("/{id}")
    public R<MdmEmployeeEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmEmployeeService.getById(id));
    }

    /**
     * 通过条件查询员工
     *
     * @param mdmEmployee 查询条件
     * @return R  对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    public R<List<MdmEmployeeEntity>> getDetails(@ParameterObject MdmEmployeeEntity mdmEmployee) {
        return R.ok(mdmEmployeeService.listDeep(Wrappers.query(mdmEmployee).eq(MdmEmployeeEntity.Fields.status, EmployeeStatusEnum.ACTIVE.getCode())));
    }

    /**
     * 新增员工
     *
     * @param mdmEmployee 员工
     * @return R
     */
    @Operation(summary = "新增员工", description = "新增员工")
    @SysLog("新增员工")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_add')")
    public R<Boolean> save(@RequestBody MdmEmployeeEntity mdmEmployee) {
        return R.ok(mdmEmployeeService.saveDeep(mdmEmployee));
    }

    /**
     * 修改员工
     *
     * @param mdmEmployee 员工
     * @return R
     */
    @Operation(summary = "修改员工", description = "修改员工")
    @SysLog("修改员工")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_edit')")
    public R<Boolean> updateById(@RequestBody MdmEmployeeEntity mdmEmployee) {
        return R.ok(mdmEmployeeService.updateDeep(mdmEmployee));
    }

    /**
     * 同步员工到用户表
     *
     * @param ids 员工ids
     * @return R
     */
    @Operation(summary = "同步员工到用户表", description = "同步员工到用户表")
    @SysLog("同步员工到用户表")
    @PostMapping("syncEmployeeToUser")
    public R<Boolean> syncEmployeeToUser(@RequestBody Long[] ids) {
        return R.ok(mdmEmployeeService.syncEmployeeToUser(ids));
    }

    /**
     * 通过id删除员工
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除员工", description = "通过id删除员工")
    @SysLog("通过id删除员工")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmEmployeeService.removeDeep(ids));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmEmployee 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_export')")
    public List<MdmEmployeeExcelVO> exportEmployee(MdmEmployeeEntity mdmEmployee, Long[] ids) {
        return mdmEmployeeService.exportEmployee(mdmEmployee, ids);
    }

    /**
     * 导入excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @Operation(summary = "导入员工", description = "导入员工")
    @SysLog("导入员工")
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmEmployee_export')")
    public R importEmployee(@RequestExcel(headRowNumber = 2) List<MdmEmployeeExcelVO> excelVOList, BindingResult bindingResult) {
        return mdmEmployeeService.importEmployee(excelVOList, bindingResult);
    }
}