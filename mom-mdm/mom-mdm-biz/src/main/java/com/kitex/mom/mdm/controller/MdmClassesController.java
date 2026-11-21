package com.kitex.mom.mdm.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.service.MdmClassesService;
import com.kitex.mom.mdm.vo.excel.MdmClassesExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 班次管理
 *
 * @author kitex
 * @date 2024-11-01 17:00:25
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmClasses")
@Tag(description = "mdmClasses", name = "班次管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmClassesController {

    private final MdmClassesService mdmClassesService;

    /**
     * 分页查询
     *
     * @param page       分页对象
     * @param mdmClasses 班次
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmClasses_view')")
    public R<Page<MdmClassesEntity>> getMdmClassesPage(SearchPage page, @ParameterObject MdmClassesEntity mdmClasses) {
        QueryWrapper<MdmClassesEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .like(StrUtil.isNotEmpty(mdmClasses.getShiftName()), MdmClassesEntity::getShiftName,
                        mdmClasses.getShiftName())
                .like(StrUtil.isNotEmpty(mdmClasses.getShiftCode()), MdmClassesEntity::getShiftCode,
                        mdmClasses.getShiftCode())
                .eq(Objects.nonNull(mdmClasses.getStatus()), MdmClassesEntity::getStatus, mdmClasses.getStatus())
                .orderByDesc(MdmClassesEntity::getCreateTime);
        return R.ok(mdmClassesService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询各班次状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各班次状态的数量", description = "查询各班次状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmClassesEntity mdmClasses) {
        QueryWrapper<MdmClassesEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmClassesEntity::getStatus)
                .like(StrUtil.isNotEmpty(mdmClasses.getShiftName()), MdmClassesEntity::getShiftName,
                        mdmClasses.getShiftName())
                .like(StrUtil.isNotEmpty(mdmClasses.getShiftCode()), MdmClassesEntity::getShiftCode,
                        mdmClasses.getShiftCode());
        return R.ok(mdmClassesService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmClassesEntity::getStatus, Collectors.counting())));
    }

    /**
     * 通过条件查询班次
     *
     * @param mdmClasses 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    public R<MdmClassesEntity> getDetails(@ParameterObject MdmClassesEntity mdmClasses) {
        return R.ok(mdmClassesService.getOne(Wrappers.query(mdmClasses), false));
    }

    /**
     * 通过id查询班次
     *
     * @param id id
     * @return R 对象列表
     */
    @Operation(summary = "通过id查询班次", description = "通过id查询班次")
    @GetMapping("/getById/{id}")
    public R<MdmClassesEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmClassesService.getById(id));
    }

    /**
     * 新增班次
     *
     * @param mdmClasses 班次
     * @return R
     */
    @Operation(summary = "新增班次", description = "新增班次")
    @SysLog("新增班次")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmClasses_add')")
    public R<Boolean> save(@RequestBody MdmClassesEntity mdmClasses) {
        return R.ok(mdmClassesService.saveClasses(mdmClasses));
    }

    /**
     * 修改班次
     *
     * @param mdmClasses 班次
     * @return R
     */
    @Operation(summary = "修改班次", description = "修改班次")
    @SysLog("修改班次")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmClasses_edit')")
    public R<Boolean> updateById(@RequestBody MdmClassesEntity mdmClasses) {
        return R.ok(mdmClassesService.updateClasses(mdmClasses));
    }

    /**
     * 通过id删除班次
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除班次", description = "通过id删除班次")
    @SysLog("通过id删除班次")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmClasses_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmClassesService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmClasses 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmClasses_export')")
    public List<MdmClassesExcelVO> exportExcel(MdmClassesEntity mdmClasses, Long[] ids) {
        return mdmClassesService
                .list(Wrappers.lambdaQuery(mdmClasses).in(ArrayUtil.isNotEmpty(ids), MdmClassesEntity::getId, ids))
                .stream()
                .map(mdmClasses1 -> {
                    MdmClassesExcelVO mdmClassesExcelVO = new MdmClassesExcelVO();
                    BeanUtil.copyProperties(mdmClasses1, mdmClassesExcelVO);
                    return mdmClassesExcelVO;
                })
                .collect(Collectors.toList());

    }

    /**
     * 导入excel 表
     *
     * @param mdmCalendarList 对象实体列表
     * @param bindingResult   错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmCalendar_export')")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<MdmClassesExcelVO> mdmCalendarList, BindingResult bindingResult) {
        return mdmClassesService.importExcel(mdmCalendarList, bindingResult);
    }

    /**
     * 获取当前班次
     *
     * @return R 对象列表
     */
    @Operation(summary = "获取当前班次", description = "获取当前班次")
    @GetMapping("/getBanci")
    public R<MdmClassesEntity> getBanci() {
        return R.ok(mdmClassesService.getBanci());
    }

    /**
     * 获取可使用的班次列表
     *
     * @return R 对象列表
     */
    @Operation(summary = "获取可使用的班次列表", description = "获取可使用的班次列表")
    @GetMapping("/getUsableClasses")
    public R<List<MdmClassesEntity>> getUsableClasses() {
        return R.ok(mdmClassesService.list(new LambdaQueryWrapper<MdmClassesEntity>()
                .eq(MdmClassesEntity::getStatus, true)));
    }

    /**
     * 获取班次规则
     *
     * @return R 对象列表
     */
    @Operation(summary = "获取班次规则", description = "获取班次规则")
    @GetMapping("/rule")
    public R<String> getClassesRule(@RequestParam("dateTime") LocalDateTime dateTime) {
        return R.ok(mdmClassesService.getClassesRule(dateTime), null);
    }

    /**
     * 根据当前时间获取包含的班次
     *
     * @param dateTime 时间
     * @param number   获取当前班次接下来的几个班次
     * @return R 对象列表
     */
    @Operation(summary = "根据当前时间获取包含的班次", description = "根据当前时间获取包含的班次")
    @GetMapping(value = "/getClassesBydate")
    public R<List<MdmClassesEntity>> getClassesBydate(@RequestParam(value = "dateTime", required = false) LocalDateTime dateTime,
                                                      @RequestParam(value = "number", required = false, defaultValue = "1") Integer number) {
        return R.ok(mdmClassesService.getClassesBydate(dateTime, number));
    }

    /**
     * 根据班次获取后几个班次信息
     *
     * @param classCode 班次
     * @param number    获取当前班次接下来的几个班次
     * @return R 对象列表
     */
    @Operation(summary = "根据班次获取后几个班次信息", description = "根据班次获取后几个班次信息")
    @GetMapping(value = "/getClassesByCode")
    public R<List<MdmClassesEntity>> getClassesByCode(@RequestParam("classCode") String classCode,
                                                      @RequestParam(value = "number", required = false, defaultValue = "1") Integer number) {
        return R.ok(mdmClassesService.getClassesByCode(classCode, number));
    }

    @Inner
    @GetMapping("/internal/patrol-sequence")
    public R<List<MdmClassesEntity>> getPatrolSequence(
            @RequestHeader(CommonConstants.TENANT_ID) Long tenant,
            @RequestHeader(CommonConstants.FACTORY_CODE) String factory,
            @RequestParam("classCode") String classCode,
            @RequestParam("number") int number) {
        requirePatrolScope(tenant, factory);
        return R.ok(mdmClassesService.getClassesByCode(classCode, number));
    }

    @Inner
    @GetMapping("/internal/patrol-shift")
    public R<String> getPatrolShift(
            @RequestHeader(CommonConstants.TENANT_ID) Long tenant,
            @RequestHeader(CommonConstants.FACTORY_CODE) String factory,
            @RequestParam("dateTime") @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime dateTime) {
        requirePatrolScope(tenant, factory);
        return R.ok(mdmClassesService.getClassesRule(dateTime), null);
    }

    private void requirePatrolScope(Long tenant, String factory) {
        if (tenant == null || tenant <= 0 || !tenant.equals(TenantContextHolder.getTenantId())
                || TenantContextHolder.getTenantSkip() || StrUtil.isBlank(factory)
                || !factory.equals(FactoryContextHolder.getFactoryCode())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("中检班次查询须指定实际租户和工厂");
        }
    }

    /**
     * 查询班次列表
     *
     * @return R 查询班次列表
     */
    @Operation(summary = "查询班次列表", description = "查询班次列表")
    @GetMapping(value = "/list")
    public R<List<MdmClassesEntity>> getList() {
        return R.ok(mdmClassesService.list(new LambdaQueryWrapper<MdmClassesEntity>()
                .eq(MdmClassesEntity::getStatus, true)));
    }


}
