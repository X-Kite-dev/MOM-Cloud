package com.kitex.mom.wms.api.feign.factory;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.dto.WmsProductionNoticeAcceptanceDTO;

import java.util.List;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class RemoteProductionPickingNoticeServiceFallbackFactory
        implements FallbackFactory<RemoteProducePickingNoticeService> {

    @Override
    public RemoteProducePickingNoticeService create(Throwable throwable) {
        return new RemoteProducePickingNoticeService() {
            @Override
            public R<Page<WmsOutNoticeEntity>> page(Integer current, Integer size,
                                                    WmsOutNoticeEntity wmsOutNoticeEntity) {
                return R.failed("获取生产领料通知单列表失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> add(WmsOutNoticeEntity wmsOutNoticeEntity) {
                return R.failed("新增生产领料通知单失败:" + throwable.getMessage());
            }

            @Override
            public R voidObj(Long id) {
                return R.failed("作废生产领料通知单失败:" + throwable.getMessage());
            }

            @Override
            public R voidObjByDocumentId(Long documentId, String documentTable) {
                return R.failed("作废生产领料通知单失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> edit(WmsOutNoticeEntity wmsOutNoticeEntity) {
                return R.failed("修改生产领料通知单失败:" + throwable.getMessage());
            }

            @Override
            public R<WmsOutNoticeEntity> details(Long id) {
                return R.failed("获取生产领料通知单详情失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> delete(String[] ids) {
                return R.failed("删除生产领料通知单失败:" + throwable.getMessage());
            }

            @Override
            public R<WmsProductionNoticeAcceptanceDTO.Receipt> accept(
                    WmsProductionNoticeAcceptanceDTO.Command command) {
                return R.failed("领料受理结果未确认，请保留原请求核对");
            }

            @Override
            public R<List<WmsProductionNoticeAcceptanceDTO.Receipt>> acceptBatch(
                    List<WmsProductionNoticeAcceptanceDTO.Command> commands) {
                return R.failed("多仓领料受理结果未确认，请按原来源核对");
            }

            @Override
            public R<WmsProductionNoticeAcceptanceDTO.Source> receiptBySource(String warehouseCode, Long documentId,
                                                                              Integer sourceRevision) {
                return R.failed("原来源受理记录读取失败，不能判定未受理");
            }

            @Override
            public R<WmsProductionNoticeAcceptanceDTO.Lookup> receiptByRequest(String requestId, String warehouseCode) {
                return R.failed("原请求回执读取失败，请稍后继续核对");
            }

            @Override
            public R<WmsProductionNoticeAcceptanceDTO.Receipt> cancelSource(
                    WmsProductionNoticeAcceptanceDTO.Cancel command) {
                return R.failed("来源撤回结果未确认，请保留原请求核对");
            }

        };
    }

}
