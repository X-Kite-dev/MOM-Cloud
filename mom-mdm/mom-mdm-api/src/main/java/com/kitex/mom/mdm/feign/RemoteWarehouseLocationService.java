package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

}
