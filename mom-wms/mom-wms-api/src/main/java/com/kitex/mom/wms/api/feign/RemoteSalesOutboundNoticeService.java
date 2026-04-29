package com.kitex.mom.wms.api.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * 销售出库通知单远程调用接口
 */
@FeignClient(contextId = "remoteSalesOutboundNoticeService", value = Constants.Name)
public interface RemoteSalesOutboundNoticeService {

    /**
     * 分页查询销售出库通知单
     */
    @GetMapping("/sales/outbound/notice/page")
    R<Page<WmsOutNoticeEntity>> page(@RequestParam("current") Integer current, @RequestParam("size") Integer size,
                                     WmsOutNoticeEntity wmsOutNoticeEntity);

    /**
     * 新增销售出库通知单
     */
    @PostMapping("/sales/outbound/notice/add")
    R<Boolean> add(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity);

    /**
     * 修改销售出库通知单
     */
    @PutMapping("/sales/outbound/notice/edit")
    R<Boolean> edit(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity);

    /**
     * 查询销售出库单详情
     */
    @GetMapping("/sales/outbound/notice/details")
    R<WmsOutNoticeEntity> details(@RequestParam("id") Long id);

    /**
     * 删除销售出库单
     */
    @DeleteMapping("/sales/outbound/notice/delete")
    R<Boolean> delete(@RequestBody String[] ids);

}
