package com.kitex.mom.wms.api.feign.factory;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.wms.api.feign.RemoteSalesOutboundNoticeService;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 销售出库通知单服务降级处理
 */
@Slf4j
@Component
public class RemoteSalesOutboundNoticeServiceFallbackFactory
        implements FallbackFactory<RemoteSalesOutboundNoticeService> {

    @Override
    public RemoteSalesOutboundNoticeService create(Throwable throwable) {
        return new RemoteSalesOutboundNoticeService() {
            @Override
            public R<Page<WmsOutNoticeEntity>> page(Integer current, Integer size,
                                                    WmsOutNoticeEntity wmsOutNoticeEntity) {
                log.error("销售出库通知单查询失败", throwable);
                return R.failed("销售出库通知单查询失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> add(WmsOutNoticeEntity wmsOutNoticeEntity) {
                log.error("新增销售出库通知单失败", throwable);
                return R.failed("新增销售出库通知单失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> edit(WmsOutNoticeEntity wmsOutNoticeEntity) {
                log.error("修改销售出库通知单失败", throwable);
                return R.failed("修改销售出库通知单失败:" + throwable.getMessage());
            }

            @Override
            public R<WmsOutNoticeEntity> details(Long id) {
                log.error("查询销售出库单详情失败", throwable);
                return R.failed("查询销售出库单详情失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> delete(String[] ids) {
                log.error("删除销售出库单失败", throwable);
                return R.failed("删除销售出库单失败:" + throwable.getMessage());
            }
        };
    }

}
