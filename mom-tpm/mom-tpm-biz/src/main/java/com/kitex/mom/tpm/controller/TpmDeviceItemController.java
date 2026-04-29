package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import com.kitex.mom.tpm.service.TpmDeviceItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备-项目绑定表
 *
 * @author kitex
 * @date 2025-02-19 13:41:22
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceItem")
@Tag(description = "tpmDeviceItem", name = "设备-项目绑定表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceItemController {

    private final TpmDeviceItemService tpmDeviceItemService;

    /**
     * 分页查询
     *
     * @param page          分页对象
     * @param tpmDeviceItem 设备-项目绑定表
     * @return
     */
    @Operation(summary = "设备-项目绑定分页查询", description = "分页查询设备-项目绑定列表")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R getTpmDeviceItemPage(@ParameterObject SearchPage page, @ParameterObject TpmDeviceItemEntity tpmDeviceItem) {
        return R.ok(tpmDeviceItemService.pageList(page.toMpPage(), tpmDeviceItem));
    }


    /**
     * 根据设备编码获取配置的检项
     */
    @GetMapping("/getDeviceItemByDeviceCode")
    public R<List<TpmDeviceItemEntity>> getDeviceItemByDeviceCode(@RequestParam("deviceCode") String deviceCode,
                                                                  @RequestParam(value = "modeCode", required = false) String modeCode,
                                                                  @RequestParam(value = "type", required = false) String type) {
        return R.ok(tpmDeviceItemService.getDeviceItemByDeviceCode(deviceCode, modeCode, type));
    }


    /**
     * 新增设备-项目绑定表
     *
     * @param tpmDeviceItem 设备-项目绑定表
     * @return R
     */
    @Operation(summary = "新增设备-项目绑定表", description = "新增设备-项目绑定表")
    @SysLog("新增设备-项目绑定表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R saveRela(@RequestBody TpmDeviceItemEntity tpmDeviceItem) {
        return R.ok(tpmDeviceItemService.saveRela(tpmDeviceItem));
    }

    /**
     * 通过id删除设备-项目绑定表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除设备-项目绑定表", description = "通过id删除设备-项目绑定表")
    @SysLog("通过id删除设备-项目绑定表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(tpmDeviceItemService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 导出excel 表格
     *
     * @param tpmDeviceItem 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    @Operation(summary = "导出设备-项目绑定", description = "导出设备-项目绑定数据到Excel")
    public List<TpmDeviceItemEntity> export(TpmDeviceItemEntity tpmDeviceItem, Long[] ids) {
        return tpmDeviceItemService.list(Wrappers.lambdaQuery(tpmDeviceItem).in(ArrayUtil.isNotEmpty(ids), TpmDeviceItemEntity::getId, ids));
    }
}