package com.kitex.mom.wms.api.feign.factory;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.api.feign.RemoteProducePickingNoticeService;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
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
        };
    }

}
