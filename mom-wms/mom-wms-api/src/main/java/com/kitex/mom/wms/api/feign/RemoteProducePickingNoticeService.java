package com.kitex.mom.wms.api.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.Constants;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO;

import java.util.List;

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
    @InternalCall(userTokenRequired = true)
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
    @InternalCall(userTokenRequired = true)
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

    /**
     * 永久受理回执只证明通知受理，不证明出库或配送。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/produce/picking/outNotice/v1/accept")
    R<WmsProductionNoticeAcceptanceDTO.Receipt> accept(@RequestBody WmsProductionNoticeAcceptanceDTO.Command command);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/produce/picking/outNotice/v1/batch")
    R<List<WmsProductionNoticeAcceptanceDTO.Receipt>> acceptBatch(
            @RequestBody List<WmsProductionNoticeAcceptanceDTO.Command> commands);

    @InternalCall(userTokenRequired = true)
    @GetMapping("/produce/picking/outNotice/v1/source")
    R<WmsProductionNoticeAcceptanceDTO.Source> receiptBySource(@RequestParam("warehouseCode") String warehouseCode,
                                                               @RequestParam("documentId") Long documentId,
                                                               @RequestParam("sourceRevision") Integer sourceRevision);

    @InternalCall(userTokenRequired = true)
    @GetMapping("/produce/picking/outNotice/v1/requests/{requestId}")
    R<WmsProductionNoticeAcceptanceDTO.Lookup> receiptByRequest(@PathVariable("requestId") String requestId,
                                                                @RequestParam("warehouseCode") String warehouseCode);

    @InternalCall(userTokenRequired = true)
    @PostMapping("/produce/picking/outNotice/v1/cancel")
    R<WmsProductionNoticeAcceptanceDTO.Receipt> cancelSource(
            @RequestBody WmsProductionNoticeAcceptanceDTO.Cancel command);

}
