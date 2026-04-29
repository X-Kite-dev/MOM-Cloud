package com.kitex.mom.tpm.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.tpm.Constants;
import com.kitex.mom.tpm.dto.DeviceChangeMoldDto;
import com.kitex.mom.tpm.dto.DeviceCheckTaskStartDto;
import com.kitex.mom.tpm.entity.TpmDeviceItemEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@FeignClient(contextId = "remoteDeviceCheckService", value = Constants.Name)
public interface RemoteDeviceCheckService {


    /**
     * 新增点检任务接口  - 废弃
     */
    @GetMapping("/tpmCheckTask/add")
    R<Boolean> addTask(@RequestParam("deviceCode") String deviceCode);


    /**
     * 设备点检任务保存 - 开机
     */
    @PostMapping("/tpmCheckTask/start")
    R<Map<String, Object>> start(@RequestBody DeviceCheckTaskStartDto startDto);

    /**
     * 设备点检任务保存 - 换模
     */
    @PostMapping("/tpmCheckTask/changeMold")
    R<Boolean> changeMold(@RequestBody DeviceChangeMoldDto moldDto);


    /**
     * 根据设备编码|模具编码 获取项目配置
     *
     * @param deviceCode 设备编码
     * @param modeCode   模具编码
     * @param type       类型  CheckTypeEnum 枚举
     * @return
     */
    @GetMapping("/tpmDeviceItem/getDeviceItemByDeviceCode")
    R<List<TpmDeviceItemEntity>> getDeviceItemByDeviceCode(@RequestParam("deviceCode") String deviceCode,
                                                           @RequestParam(value = "modeCode", required = false) String modeCode,
                                                           @RequestParam(value = "type", required = false) String type);


}
