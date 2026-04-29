package com.kitex.mom.pms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.pms.Constants;
import com.kitex.mom.pms.dto.PmsInNoticeOrderDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remoteOrderService", value = Constants.Name)
public interface RemoteOrderService {

    /**
     * 接收采购通知单
     *
     * @param pmsInNoticeOrderDTO 采购通知单接收DTO
     * @return R
     */
    @PostMapping("/pmsOrder/inNoticeSave")
    R<Boolean> inNoticeSave(@RequestBody PmsInNoticeOrderDTO pmsInNoticeOrderDTO);

}
