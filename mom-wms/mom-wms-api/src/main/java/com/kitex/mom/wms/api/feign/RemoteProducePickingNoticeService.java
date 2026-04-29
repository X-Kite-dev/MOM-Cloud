package com.kitex.mom.wms.api.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * 生产领料通知单远程调用接口
 */
@FeignClient(contextId = "remoteProducePickingNoticeService", value = Constants.Name)
public interface RemoteProducePickingNoticeService {

    /**
     * 分页查询生产领料通知单
     */
    @GetMapping("/produce/picking/outNotice/page")
    R<Page<WmsOutNoticeEntity>> page(@RequestParam("current") Integer current, @RequestParam("size") Integer size,
                                     WmsOutNoticeEntity wmsOutNoticeEntity);

    /**
     * 新增生产领料通知单
     */
    @PostMapping("/produce/picking/outNotice/add")
    R<Boolean> add(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity);

    /**
     * 作废生产领料出库通知单
     */
    @GetMapping("/produce/picking/outNotice/voidObj")
    R voidObj(@RequestParam Long id);

    /**
     * 作废生产领料出库通知单-通过业务单据
     */
    @GetMapping("/produce/picking/outNotice/voidObjByDocumentId")
    R voidObjByDocumentId(@RequestParam Long documentId, @RequestParam String documentTable);

    /**
     * 修改生产领料通知单
     */
    @PutMapping("/produce/picking/outNotice/edit")
    R<Boolean> edit(@RequestBody WmsOutNoticeEntity wmsOutNoticeEntity);

    /**
     * 查询生产领料单详情
     */
    @GetMapping("/produce/picking/outNotice/details")
    R<WmsOutNoticeEntity> details(@RequestParam("id") Long id);

    /**
     * 删除生产领料单
     */
    @DeleteMapping("/produce/picking/outNotice/delete")
    R<Boolean> delete(@RequestBody String[] ids);

}
