package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 入库通知单
 */
@FeignClient(contextId = "remoteInNoticeService", value = Constants.Name)
public interface RemoteInNoticeService {

    /**
     * 新增入库通知单
     */
    @PostMapping("/wmsPurchaseInNotice/addInNotice")
    R<Boolean> save(@RequestBody WmsInNoticeEntity wmsInNotice);


    /**
     * 生产退料
     */
    @PostMapping("/wmsProduceReturn/inNotice/addInNotice")
    R<Boolean> produceReturn(@RequestBody WmsInNoticeEntity wmsInNotice);
}
