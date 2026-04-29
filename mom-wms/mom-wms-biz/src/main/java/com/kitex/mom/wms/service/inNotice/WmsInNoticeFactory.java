package com.kitex.mom.wms.service.inNotice;

import cn.hutool.extra.spring.SpringUtil;

import java.util.Map;

/**
 * 入库通知单工厂类
 */
public class WmsInNoticeFactory {

    /**
     * 获取对应的实现类
     */
    public static WmsInNoticeHandler getHandler(String billType) {
        Map<String, AbstractWmsInNoticeHandler> beansOfType = SpringUtil.getBeansOfType(AbstractWmsInNoticeHandler.class);
        for (WmsInNoticeHandler handler : beansOfType.values()) {
            if (handler.support(billType)) {
                return handler;
            }
        }
        return SpringUtil.getBean(DefaultWmsInNoticeService.class);
    }

}
