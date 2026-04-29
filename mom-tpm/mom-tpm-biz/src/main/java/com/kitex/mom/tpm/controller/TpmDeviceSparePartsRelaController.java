package com.kitex.mom.tpm.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.tpm.entity.TpmDeviceSparePartsRelaEntity;
import com.kitex.mom.tpm.service.TpmDeviceSparePartsRelaService;
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
 * TPM设备-备件关联表
 *
 * @author kitex
 * @date 2025-03-18 10:00:49
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceSparePartsRela")
@Tag(description = "tpmDeviceSparePartsRela", name = "TPM设备-备件关联表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceSparePartsRelaController {

    private final TpmDeviceSparePartsRelaService tpmDeviceSparePartsRelaService;

    /**
     * 分页查询 - 根据设备编码
     */
    @Operation(summary = "分页查询 - 根据设备编码", description = "分页查询 - 根据设备编码")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Page<TpmDeviceSparePartsRelaEntity>> getTpmDeviceSparePartsRelaPage(@ParameterObject SearchPage page, @ParameterObject TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela) {
        return R.ok(tpmDeviceSparePartsRelaService.getTpmDeviceSparePartsRelaPage(page.toMpPage(), tpmDeviceSparePartsRela));
    }


    /**
     * 列表查询 - 根据设备编码
     */
    @Operation(summary = "列表查询 - 根据设备编码", description = "列表查询 - 根据设备编码")
    @GetMapping("/getListByDeviceCode")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<List<TpmDeviceSparePartsRelaEntity>> getListByDeviceCode(@RequestParam("deviceCode") String deviceCode) {
        return R.ok(tpmDeviceSparePartsRelaService.list(new LambdaQueryWrapper<TpmDeviceSparePartsRelaEntity>()
                .eq(TpmDeviceSparePartsRelaEntity::getDeviceCode, deviceCode)));
    }

    /**
     * 通过id查询TPM设备-备件关联表
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<TpmDeviceSparePartsRelaEntity> getRelaById(@PathVariable("id") Long id) {
        return R.ok(tpmDeviceSparePartsRelaService.getRelaById(id));
    }

    /**
     * 新增TPM设备-备件关联表
     *
     * @param tpmDeviceSparePartsRela TPM设备-备件关联表
     * @return R
     */
    @Operation(summary = "新增TPM设备-备件关联表", description = "新增TPM设备-备件关联表")
    @SysLog("新增TPM设备-备件关联表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Boolean> saveRela(@RequestBody TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela) {
        return R.ok(tpmDeviceSparePartsRelaService.saveRela(tpmDeviceSparePartsRela));
    }

    /**
     * 修改TPM设备-备件关联表
     *
     * @param tpmDeviceSparePartsRela TPM设备-备件关联表
     * @return R
     */
    @Operation(summary = "修改TPM设备-备件关联表", description = "修改TPM设备-备件关联表")
    @SysLog("修改TPM设备-备件关联表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Boolean> updateById(@RequestBody TpmDeviceSparePartsRelaEntity tpmDeviceSparePartsRela) {
        return R.ok(tpmDeviceSparePartsRelaService.updateById(tpmDeviceSparePartsRela));
    }

    /**
     * 通过id删除TPM设备-备件关联表
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除TPM设备-备件关联表", description = "通过id删除TPM设备-备件关联表")
    @SysLog("通过id删除TPM设备-备件关联表")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(tpmDeviceSparePartsRelaService.removeBatchByIds(CollUtil.toList(ids)));
    }


    /**
     * 根据明细id集合创建申请单
     */
    @Operation(summary = "根据明细id集合创建申请单", description = "根据明细id集合创建申请单")
    @GetMapping("/create/apply")
    @PreAuthorize("@pms.hasPermission('tpm_tpmDeviceItem')")
    public R<Boolean> createApply(Long[] ids) {
        return R.ok(tpmDeviceSparePartsRelaService.createApply(CollUtil.toList(ids)));
    }

}