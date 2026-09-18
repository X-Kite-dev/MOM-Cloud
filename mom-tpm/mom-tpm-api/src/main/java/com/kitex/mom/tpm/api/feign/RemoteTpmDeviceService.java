package com.kitex.mom.tpm.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.Constants;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;
import com.kitex.mom.tpm.entity.TpmEventRecordEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remoteTpmDeviceService", value = Constants.Name)
public interface RemoteTpmDeviceService {

    @PostMapping("/tpmDeviceBase/pause/receipt")
    R<com.kitex.mom.tpm.dto.TpmDevicePauseReceipt> devicePauseReceipt(
            @RequestBody com.kitex.mom.tpm.dto.TpmDevicePauseCommand command);


    /**
     * 新增设备生命周期记录
     */
    @PostMapping("/tpmEventRecord")
    R<Boolean> addBatchRecord(@RequestBody List<TpmEventRecordEntity> tpmEventRecord);


    /**
     * 设备暂停
     */
    @PostMapping("/tpmDeviceBase/pause")
    R<Boolean> devicePause(@RequestParam("deviceCode") String deviceCode,
                           @RequestParam(value = "moldCode", required = false) String moldCode);

    /**
     * 设备关机
     */
    @PostMapping("/tpmDeviceBase/close")
    R<Boolean> deviceClose(@RequestParam("deviceCode") String deviceCode,
                           @RequestParam(value = "moldCode", required = false) String moldCode);


    /**
     * 根据设备编码查询设备-模具关联表
     */
    @GetMapping("/tpmDeviceMoldRela/getListByDeviceCode")
    R<List<TpmDeviceMoldRelaEntity>> getListByDeviceCode(@RequestParam("deviceCode") String deviceCode);

    /**
     * 根据设备编码查询设备-模具关联表
     */
    @GetMapping("/tpmDeviceMoldRela/getListByDeviceCodes")
    R<List<TpmDeviceMoldRelaEntity>> getListByDeviceCodes(@RequestParam("deviceCodes") List<String> deviceCodes);


}
