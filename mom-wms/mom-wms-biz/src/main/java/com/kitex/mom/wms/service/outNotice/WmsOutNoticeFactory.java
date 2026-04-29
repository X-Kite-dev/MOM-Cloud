package com.kitex.mom.wms.service.outNotice;

import cn.hutool.extra.spring.SpringUtil;

import java.util.Map;

/**
 * 出库通知单工厂类
 */
public class WmsOutNoticeFactory {

    /**
     * 获取对应的实现类
     */
    public static WmsOutNoticeHandler getHandler(String billType) {
        Map<String, AbstractWmsOutNoticeHandler> beansOfType = SpringUtil
                .getBeansOfType(AbstractWmsOutNoticeHandler.class);
        for (WmsOutNoticeHandler handler : beansOfType.values()) {
            if (handler.support(billType)) {
                return handler;
            }
        }
        return SpringUtil.getBean(DefaultWmsOutNoticeService.class);
    }

}
