package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(contextId = "remoteWarehouseLocationService", value = Constants.Name)
public interface RemoteWarehouseLocationService {

    /**
     * 通过编码查询库位
     *
     * @param code 库位编码
     * @return R 对象
     */
    @GetMapping("/mdmWarehouseLocation/getByCode")
    R<MdmWarehouseLocationEntity> getByCode(@RequestParam String code);

    /**
     * 当前租户、工厂、仓库的真实库位清单，供 WMS 库位图使用。
     */
    @InternalCall
    @GetMapping("/mdmWarehouseLocation/layoutLocations")
    R<List<MdmWarehouseLocationEntity>> getLayoutLocations(@RequestParam("warehouseCode") String warehouseCode);

}
