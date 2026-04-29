package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@FeignClient(contextId = "remoteDeviceClassService", value = Constants.Name)
public interface RemoteDeviceClassService {

    /**
     * 获取设备分类
     */
    @GetMapping("/mdmDeviceClass/getList")
    R<List<MdmDeviceClassEntity>> getMdmDeviceClassList();

    /**
     * 获取设备分类 树形结构
     */
    @GetMapping("/mdmDeviceClass/tree")
    R<List<MdmDeviceClassEntity>> getDeviceClassTreeList();

}
