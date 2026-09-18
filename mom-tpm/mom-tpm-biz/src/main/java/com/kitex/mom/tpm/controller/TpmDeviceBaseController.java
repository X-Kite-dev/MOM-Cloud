package com.kitex.mom.tpm.controller;

import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.tpm.service.TpmDeviceBaseService;
import com.kitex.mom.tpm.vo.MdmDeviceExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 设备台账
 *
 * @author kitex
 * @date 2025-02-06 15:04:12
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/tpmDeviceBase")
@Tag(description = "tpmDeviceBase", name = "设备管理-设备台账")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class TpmDeviceBaseController {

    private final TpmDeviceBaseService tpmDeviceBaseService;


    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param mdmDeviceEntity 设备基础表
     * @return
     */
    @Operation(summary = "设备台账分页查询", description = "分页查询设备台账列表")
    @GetMapping("/all/page")
    public R getAllPage(@ParameterObject SearchPage page, @ParameterObject MdmDeviceEntity mdmDeviceEntity) {
        return R.ok(tpmDeviceBaseService.getAllPage(page.toMpPage(), mdmDeviceEntity));
    }

    /**
     * 分页查询 - 统计
     */
    @Operation(summary = "分页查询 - 统计", description = "分页查询 - 统计")
    @GetMapping("/getCount")
    public R getCount(@ParameterObject MdmDeviceEntity mdmDeviceEntity) {
        return R.ok(tpmDeviceBaseService.getCount(mdmDeviceEntity));
    }


    /**
     * 分页查询
     *
     * @param page            分页对象
     * @param mdmDeviceEntity 设备基础表
     * @return
     */
    @Operation(summary = "设备基础信息分页查询", description = "分页查询设备基础信息列表")
    @GetMapping("/page")
    public R getTpmMaintenanceItemPage(@ParameterObject SearchPage page, @ParameterObject MdmDeviceEntity mdmDeviceEntity) {
        return R.ok(tpmDeviceBaseService.page(page.toMpPage(), mdmDeviceEntity));
    }


    /**
     * 通过id查询设备基础信息
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "查询设备基础信息详情", description = "通过id查询设备基础信息详情")
    @GetMapping("/{id}")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(tpmDeviceBaseService.getById(id));
    }


    /**
     * 获取设备分类 - 树
     */
    @Operation(summary = "获取设备分类 - 树", description = "获取设备分类 - 树")
    @GetMapping("/getDevice")
    public R<List<MdmDeviceClassEntity>> getDevice() {
        return R.ok(tpmDeviceBaseService.getDevice());
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
    public R saveDevice(@RequestBody MdmDeviceEntity mdmDevice) {
        return R.ok(tpmDeviceBaseService.saveDevice(mdmDevice));
    }

    /**
     * 修改设备信息
     *
     * @param mdmDeviceEntity 设备基础信息
     * @return R
     */
    @Operation(summary = "修改设备信息", description = "修改设备信息")
    @SysLog("修改设备信息")
    @PutMapping
    public R updateById(@RequestBody MdmDeviceEntity mdmDeviceEntity) {
        return R.ok(tpmDeviceBaseService.updateById(mdmDeviceEntity));
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
    public R removeById(@RequestBody Long[] ids) {
        return R.ok(tpmDeviceBaseService.removeBatchByIds(ids));
    }


    /**
     * 导入excel 表
     *
     * @param mdmDeviceList 对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @PostMapping("/import")
    public R importExcel(@RequestExcel(headRowNumber = 2) List<MdmDeviceExcelVO> mdmDeviceList, BindingResult bindingResult, String factoryCode) {
        return tpmDeviceBaseService.importExcel(mdmDeviceList, bindingResult, factoryCode);
    }


    /**
     * 设备关机-生产使用
     */
    @Operation(summary = "设备关机-生产使用", description = "设备关机-生产使用")
    @PostMapping("/pause")
    public R<Boolean> devicePause(@RequestParam("deviceCode") String deviceCode,
                                  @RequestParam(value = "moldCode", required = false) String moldCode) {
        return R.ok(tpmDeviceBaseService.devicePause(deviceCode, moldCode));
    }

    @PostMapping("/pause/receipt")
    public R<com.kitex.mom.tpm.dto.TpmDevicePauseReceipt> devicePauseReceipt(
            @RequestBody com.kitex.mom.tpm.dto.TpmDevicePauseCommand command) {
        return R.ok(tpmDeviceBaseService.devicePauseReceipt(command));
    }

    /**
     * 设备暂停 - 生产使用
     */
    @Operation(summary = "设备暂停 - 生产使用", description = "设备暂停 - 生产使用")
    @PostMapping("/close")
    public R<Boolean> deviceClose(@RequestParam("deviceCode") String deviceCode,
                                  @RequestParam(value = "moldCode", required = false) String moldCode) {
        return R.ok(tpmDeviceBaseService.deviceClose(deviceCode, moldCode));
    }

    @PostMapping("/close/receipt")
    public R<com.kitex.mom.tpm.dto.TpmDevicePauseReceipt> deviceCloseReceipt(
            @RequestBody com.kitex.mom.tpm.dto.TpmDevicePauseCommand command) {
        return R.ok(tpmDeviceBaseService.deviceCloseReceipt(command));
    }

    /**
     * 生成设备二维码
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Operation(summary = "生成设备二维码", description = "生成设备二维码")
    @GetMapping("/print")
    public R<String> print(@ParameterObject PrintReq printReq) {
        return R.ok(tpmDeviceBaseService.print(printReq));
    }

}
