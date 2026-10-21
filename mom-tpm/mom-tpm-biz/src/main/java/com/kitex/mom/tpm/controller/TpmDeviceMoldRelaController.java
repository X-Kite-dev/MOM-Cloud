package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;
import com.kitex.mom.tpm.service.TpmDeviceMoldRelaService;
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
 * TPM设备-模具关联表
 *
 * @author kitex
 * @date 2025-03-18 10:00:49
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceMoldRela")
@Tag(description = "tpmDeviceMoldRela", name = "TPM设备-模具关联表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceMoldRelaController {

    private final TpmDeviceMoldRelaService tpmDeviceMoldRelaService;

    /**
     * 分页查询 - 根据设备编码
     */
    @Operation(summary = "分页查询 - 根据设备编码", description = "分页查询 - 根据设备编码")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Page<TpmDeviceMoldRelaEntity>> getTpmDeviceMoldRelaPage(@ParameterObject SearchPage page, @ParameterObject TpmDeviceMoldRelaEntity tpmDeviceMoldRela) {
        return R.ok(tpmDeviceMoldRelaService.getTpmDeviceMoldRelaPage(page.toMpPage(), tpmDeviceMoldRela));
    }


    /**
     * 列表查询 - 根据设备编码
     */
    @Operation(summary = "列表查询 - 根据设备编码", description = "列表查询 - 根据设备编码")
    @GetMapping("/getListByDeviceCode")
    public R<List<TpmDeviceMoldRelaEntity>> getListByDeviceCode(@RequestParam("deviceCode") String deviceCode) {
        return R.ok(tpmDeviceMoldRelaService.getListByDeviceCodes(List.of(deviceCode)));
    }


    /**
     * 列表查询 - 根据设备编码
     */
    @Operation(summary = "列表查询 - 根据设备编码", description = "列表查询 - 根据设备编码")
    @GetMapping("/getListByDeviceCodes")
    public R<List<TpmDeviceMoldRelaEntity>> getListByDeviceCodes(@RequestParam("deviceCodes") List<String> deviceCodes) {
        return R.ok(tpmDeviceMoldRelaService.getListByDeviceCodes(deviceCodes));
    }

    /**
     * 新增TPM设备-模具关联表
     *
     * @param tpmDeviceMoldRelaEntity TPM设备-模具关联表
     * @return R
     */
    @Operation(summary = "新增TPM设备-模具关联表", description = "新增TPM设备-模具关联表")
    @SysLog("新增TPM设备-模具关联表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Boolean> saveRela(@RequestBody TpmDeviceMoldRelaEntity tpmDeviceMoldRelaEntity) {
        return R.ok(tpmDeviceMoldRelaService.saveRela(tpmDeviceMoldRelaEntity));
    }

    /**
     * 通过id删除TPM设备-模具关联表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除TPM设备-模具关联表", description = "通过id删除TPM设备-模具关联表")
    @SysLog("通过id删除TPM设备-模具关联表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmDeviceMoldRelaService.removeScoped(CollUtil.toList(ids)));
    }


}
