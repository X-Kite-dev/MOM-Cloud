package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(contextId = "remoteDeviceService", value = Constants.Name)
public interface RemoteDeviceService {

    @PostMapping("/mdmDevice/controlled-pause")
    R<com.kitex.mom.mdm.entity.MdmDevicePauseReceipt> controlledPause(
            @RequestBody com.kitex.mom.mdm.dto.MdmDevicePauseCommand command);

    @PostMapping("/mdmDevice/controlled-pause/close")
    R<com.kitex.mom.mdm.entity.MdmDevicePauseReceipt> controlledClose(
            @RequestBody com.kitex.mom.mdm.dto.MdmDevicePauseCommand command);

    /**
     * 分页查询设备信息
     *
     * @param current   当前页
     * @param size      每页大小
     * @param mdmDevice 设备实体
     * @return R
     */
    @GetMapping("/mdmDevice/all/page")
    R<Page<MdmDeviceEntity>> getAllPage(@RequestParam("current") Integer current,
                                        @RequestParam("size") Integer size, @SpringQueryMap MdmDeviceEntity mdmDevice);


    /**
     * 分页查询设备信息
     *
     * @param current   当前页
     * @param size      每页大小
     * @param mdmDevice 设备实体
     * @return R
     */
    @GetMapping("/mdmDevice/page")
    R<Page<MdmDeviceEntity>> getMdmDevicePage(@RequestParam("current") Integer current,
                                              @RequestParam("size") Integer size, @SpringQueryMap MdmDeviceEntity mdmDevice);


    /**
     * 分页查询 - 统计
     */
    @GetMapping("/mdmDevice/getCount")
    R<Map<Integer, Long>> getCount(@SpringQueryMap MdmDeviceEntity mdmDevice);


    /**
     * 新增设备信息
     *
     * @param mdmDevice 设备实体
     * @return R
     */
    @PostMapping("/mdmDevice")
    R<Boolean> save(@RequestBody MdmDeviceEntity mdmDevice);

    /**
     * 批量新增
     */
    @PostMapping("/mdmDevice/saveBatch")
    R<Boolean> saveBatch(@RequestBody List<MdmDeviceEntity> mdmDevice);

    /**
     * 更新设备信息
     *
     * @param mdmDevice 设备实体
     * @return R
     */
    @PutMapping("/mdmDevice")
    R<Boolean> updateById(@RequestBody MdmDeviceEntity mdmDevice);

    /**
     * 更新设备信息
     */
    @PutMapping("/mdmDevice/batch/update")
    R<Boolean> updateBatchById(@RequestBody MdmDeviceEntity mdmDevice);


    /**
     * 删除设备信息
     *
     * @param ids 设备 ID数组
     * @return R
     */
    @DeleteMapping("/mdmDevice")
    R<Boolean> removeById(@RequestBody Long[] ids);


    /**
     * 查询条件设备详情
     */
    @GetMapping("/mdmDevice/details")
    R<MdmDeviceEntity> getDetails(@SpringQueryMap MdmDeviceEntity mdmDevice);


    /**
     * 查询设备详情
     *
     * @param id 设备 ID
     * @return R
     */
    @GetMapping("/mdmDevice/{id}")
    R<MdmDeviceEntity> getById(@PathVariable Long id);


    /**
     * 获取设备 - 启用中的设备
     */
    @GetMapping("/mdmDevice/list")
    R<List<MdmDeviceEntity>> getMdmDeviceList();

    /**
     * 获取设备 - 启用中的设备
     */
    @GetMapping("/mdmDevice/listByCodes")
    R<List<MdmDeviceEntity>> getMdmDeviceListByCodes(@RequestParam("deviceCodes") List<String> deviceCodes);


    /**
     * 获取设备所有
     */
    @GetMapping("/mdmDevice/all/list")
    R<List<MdmDeviceEntity>> getAllMdmDeviceList();


    /**
     * 获取设备所有 - 包含报废设备
     */
    @GetMapping("/mdmDevice/allListWithScrap")
    R<List<MdmDeviceEntity>> getAllListWithScrap();


    /**
     * 获取设备所有
     */
    @PostMapping("/mdmDevice/getListByClass")
    R<List<MdmDeviceEntity>> getListByClass(@RequestBody List<String> classCodeList);


}
