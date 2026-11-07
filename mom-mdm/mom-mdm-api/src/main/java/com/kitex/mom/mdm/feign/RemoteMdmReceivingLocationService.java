package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmReceivingLocationDTO.RuntimeView;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** 保留实际用户令牌，接收端重核实际工厂read；不是NoToken/Inner旁路。 */
@FeignClient(value = Constants.Name, contextId = "remoteMdmReceivingLocationService")
public interface RemoteMdmReceivingLocationService {
    @GetMapping("/mdmStationReceiving/runtime/{stationCode}")
    R<RuntimeView> runtime(@PathVariable String stationCode);
}
