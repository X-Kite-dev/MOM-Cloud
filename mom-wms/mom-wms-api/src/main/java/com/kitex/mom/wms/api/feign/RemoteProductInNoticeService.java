package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 成品入库通知单
 */
@FeignClient(contextId = "remoteProductInNoticeService", value = Constants.Name)
public interface RemoteProductInNoticeService {

    /**
     * 新增入库通知单
     */
    @PostMapping("/product/inNotice/addInNotice")
    R<Boolean> save(@RequestBody WmsInNoticeEntity wmsInNotice);

}
