package com.kitex.mom.mdm.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
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
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.service.MdmDeviceService;
import com.kitex.mom.mdm.utils.DynamicRefreshUtils;
import com.kitex.mom.mdm.vo.excel.MdmDeviceExcelVO;
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
 * 设备管理 Controller - 提供设备信息的增删改查接口
 * 用于管理生产设备的基础信息、技术参数、状态信息等
 *
 * @author kitex
 * @date 2024-10-28 17:29:52
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmDevice")
@Tag(description = "mdmDevice", name = "设备管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmDeviceController {

    private final MdmDeviceService mdmDeviceService;


    /**
     * 分页查询设备信息
     *
     * @param page      分页对象，包含分页参数
     * @param mdmDevice 查询条件，支持设备编码、名称、分类、状态等过滤
     * @return 分页查询结果，包含设备列表和分页信息
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/all/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_view')")
    public R<Page<MdmDeviceEntity>> getAllPage(SearchPage page, @ParameterObject MdmDeviceEntity mdmDevice) {
        QueryWrapper<MdmDeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmDevice.getDeviceCode()), MdmDeviceEntity::getDeviceCode, mdmDevice.getDeviceCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmDevice.getDeviceName()), MdmDeviceEntity::getDeviceName, mdmDevice.getDeviceName());
        wrapper.lambda().eq(StrUtil.isNotBlank(mdmDevice.getClassCode()), MdmDeviceEntity::getClassCode, mdmDevice.getClassCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmDevice.getClassName()), MdmDeviceEntity::getClassName, mdmDevice.getClassName());
        wrapper.lambda().eq(ObjectUtil.isNotEmpty(mdmDevice.getDeviceState()), MdmDeviceEntity::getDeviceState, mdmDevice.getDeviceState());
        wrapper.lambda().eq(ObjectUtil.isNotEmpty(mdmDevice.getStatus()), MdmDeviceEntity::getStatus, mdmDevice.getStatus());
        wrapper.lambda().orderByDesc(MdmDeviceEntity::getCreateTime);
        return R.ok(mdmDeviceService.page(page.toMpPage(), wrapper));
    }

    /**
     * 分页查询
     *
     * @param page      分页对象
     * @param mdmDevice 设备
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_view')")
    public R<Page<MdmDeviceEntity>> getMdmDevicePage(SearchPage page, @ParameterObject MdmDeviceEntity mdmDevice) {
        QueryWrapper<MdmDeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().like(StrUtil.isNotBlank(mdmDevice.getDeviceCode()), MdmDeviceEntity::getDeviceCode, mdmDevice.getDeviceCode());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmDevice.getDeviceName()), MdmDeviceEntity::getDeviceName, mdmDevice.getDeviceName());
        wrapper.lambda().like(StrUtil.isNotBlank(mdmDevice.getClassName()), MdmDeviceEntity::getClassName, mdmDevice.getClassName());
        wrapper.lambda().eq(ObjectUtil.isNotEmpty(mdmDevice.getDeviceState()), MdmDeviceEntity::getDeviceState, mdmDevice.getDeviceState());
        wrapper.lambda().eq(ObjectUtil.isNotEmpty(mdmDevice.getStatus()), MdmDeviceEntity::getStatus, mdmDevice.getStatus());
        wrapper.lambda().ne(MdmDeviceEntity::getDeviceState, DeviceStateEnum.SCRAP.getCode());
        wrapper.lambda().eq(StrUtil.isNotBlank(mdmDevice.getClassCode()), MdmDeviceEntity::getClassCode, mdmDevice.getClassCode());
        wrapper.lambda().notIn(CollUtil.isNotEmpty(mdmDevice.getNeCodeList()), MdmDeviceEntity::getDeviceCode, mdmDevice.getNeCodeList());
        return R.ok(mdmDeviceService.page(page.toMpPage(), wrapper));
    }

    /**
     * 查询设备列表
     *
     * @return R 设备列表
     */
    @Operation(summary = "查询设备列表", description = "查询设备列表")
    @GetMapping("/list")
    public R<List<MdmDeviceEntity>> getList() {
        return R.ok(mdmDeviceService
                .list(new LambdaQueryWrapper<MdmDeviceEntity>()
                        .eq(MdmDeviceEntity::getStatus, Boolean.TRUE)
                        .ne(MdmDeviceEntity::getDeviceState, DeviceStateEnum.SCRAP.getCode())));
    }

    /**
     * 查询设备列表
     *
     * @return R 设备列表
     */
    @Operation(summary = "查询设备列表", description = "查询设备列表")
    @GetMapping("/listByCodes")
    public R<List<MdmDeviceEntity>> getListByCodes(@RequestParam("deviceCodes") List<String> deviceCodes) {
        return R.ok(mdmDeviceService.list(new LambdaQueryWrapper<MdmDeviceEntity>()
                .in(MdmDeviceEntity::getDeviceCode, deviceCodes)));
    }

    /**
     * 查询设备列表 所有
     */
    @Operation(summary = "查询设备列表", description = "查询设备列表")
    @GetMapping("/all/list")
    public R getAllList() {
        return R.ok(mdmDeviceService.list(new LambdaQueryWrapper<MdmDeviceEntity>()
                .ne(MdmDeviceEntity::getDeviceState, DeviceStateEnum.SCRAP.getCode())));
    }


    /**
     * 获取设备所有 - 包含报废设备
     */
    @Operation(summary = "获取设备所有 - 包含报废设备", description = "获取设备所有 - 包含报废设备")
    @GetMapping("/allListWithScrap")
    public R allListWithScrap() {
        return R.ok(mdmDeviceService.list(new LambdaQueryWrapper<>()));
    }


    /**
     * 查询设备列表 所有
     */
    @Operation(summary = "查询设备列表", description = "查询设备列表")
    @PostMapping("/getListByClass")
    public R getListByClass(@RequestBody List<String> classCodeList) {
        return R.ok(mdmDeviceService.list(new LambdaQueryWrapper<MdmDeviceEntity>()
                .in(MdmDeviceEntity::getClassCode, classCodeList)
                .ne(MdmDeviceEntity::getDeviceState, DeviceStateEnum.SCRAP.getCode())));
    }

    /**
     * 查询各设备状态的数量
     *
     * @return
     */
    @Operation(summary = "查询各设备状态的数量", description = "查询各设备状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmDeviceEntity mdmDevice) {
        QueryWrapper<MdmDeviceEntity> wrapper = new QueryWrapper<>();
        wrapper.lambda().select(MdmDeviceEntity::getStatus)
                .like(StrUtil.isNotBlank(mdmDevice.getDeviceCode()), MdmDeviceEntity::getDeviceCode, mdmDevice.getDeviceCode())
                .like(StrUtil.isNotBlank(mdmDevice.getDeviceName()), MdmDeviceEntity::getDeviceName, mdmDevice.getDeviceName())
                .eq(StrUtil.isNotBlank(mdmDevice.getClassCode()), MdmDeviceEntity::getClassCode, mdmDevice.getClassCode())
                .like(StrUtil.isNotBlank(mdmDevice.getClassName()), MdmDeviceEntity::getClassName, mdmDevice.getClassName())
                .eq(ObjectUtil.isNotEmpty(mdmDevice.getDeviceState()), MdmDeviceEntity::getDeviceState, mdmDevice.getDeviceState());
        return R.ok(mdmDeviceService.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmDeviceEntity::getStatus, Collectors.counting())));
    }


    /**
     * 通过条件查询设备
     *
     * @param mdmDevice 查询条件
     * @return R 对象列表
     */
    @Operation(summary = "通过条件查询", description = "通过条件查询对象")
    @GetMapping("/details")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_view')")
    public R<MdmDeviceEntity> getDetails(@ParameterObject MdmDeviceEntity mdmDevice) {
        return R.ok(mdmDeviceService.getOne(Wrappers.query(mdmDevice), false));
    }


    /**
     * 通过id查询设备
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询设备", description = "通过id查询设备")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_view')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(mdmDeviceService.getById(id));
    }


    /**
     * 新增设备
     *
     * @param mdmDevice 设备
     * @return R
     */
    @Operation(summary = "新增设备", description = "新增设备")
    @SysLog("新增设备")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_add')")
    public R<Boolean> saveDevice(@RequestBody MdmDeviceEntity mdmDevice) {
        mdmDeviceService.saveDevice(mdmDevice);
        DynamicRefreshUtils.refreshMdm(mdmDevice);
        return R.ok();
    }

    /**
     * 批量新增设备
     */
    @Operation(summary = "新增设备", description = "新增设备")
    @PostMapping("/saveBatch")
//    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_add,tpm_tpmDeviceArrivalInspection_approval')")
    public R<Boolean> saveDevice(@RequestBody List<MdmDeviceEntity> mdmDeviceList) {
        return R.ok(mdmDeviceService.saveBatch(mdmDeviceList));
    }

    /**
     * 修改设备
     *
     * @param mdmDevice 设备
     * @return R
     */
    @Operation(summary = "修改设备", description = "修改设备")
    @SysLog("修改设备")
    @PutMapping
    public R<Boolean> updateById(@RequestBody MdmDeviceEntity mdmDevice) {
        // 通用资料编辑不得隐式将不可用设备改成可用；维修恢复必须走独立回执。
        if (!Boolean.TRUE.equals(mdmDeviceService.updateById(mdmDevice))) return R.failed("设备资料更新失败");
        DynamicRefreshUtils.refreshMdm(mdmDevice);
        return R.ok(Boolean.TRUE);
    }

    /**
     * 修改设备
     */
    @PutMapping("/batch/update")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_edit')")
    public R<Boolean> updateBatchById(@RequestBody MdmDeviceEntity mdmDevice) {
        return R.ok(mdmDeviceService.updateBatchById(mdmDevice.getDeviceList()));
    }

    /**
     * 通过id删除设备
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除设备", description = "通过id删除设备")
    @SysLog("通过id删除设备")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        //todo 删除设备同步删除该设备的保养计划
        return R.ok(mdmDeviceService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param mdmDevice 查询条件
     * @param ids       导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_export')")
    public List<MdmDeviceExcelVO> exportExcel(MdmDeviceEntity mdmDevice, Long[] ids) {
        return mdmDeviceService
                .list(Wrappers.lambdaQuery(mdmDevice).in(ArrayUtil.isNotEmpty(ids), MdmDeviceEntity::getId, ids))
                .stream()
                .map(mdmDevice1 -> {
                    MdmDeviceExcelVO mdmDeviceExcelVO = new MdmDeviceExcelVO();
                    BeanUtil.copyProperties(mdmDevice1, mdmDeviceExcelVO);
                    return mdmDeviceExcelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 导入excel 表 ---------- 转移至tpm模块，这个地方弃用
     *
     * @param mdmDeviceList 对象实体列表
     * @return ok fail
     */
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmDevice_export')")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<MdmDeviceExcelVO> mdmDeviceList, BindingResult bindingResult) {
        return mdmDeviceService.importExcel(mdmDeviceList, bindingResult);
    }

}
