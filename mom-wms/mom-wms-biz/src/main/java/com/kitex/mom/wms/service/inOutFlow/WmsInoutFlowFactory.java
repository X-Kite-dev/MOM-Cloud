package com.kitex.mom.wms.service.inOutFlow;

import cn.hutool.extra.spring.SpringUtil;

import java.util.Map;

/**
 * 出入库流水工厂类
 */
public class WmsInoutFlowFactory {


    /**
     * 获取对应的实现类
     */
    public static WmsInoutFlowHandler getHandler(String billType) {
        Map<String, AbstractWmsInoutFlowHandler> beansOfType = SpringUtil.getBeansOfType(AbstractWmsInoutFlowHandler.class);
        for (WmsInoutFlowHandler handler : beansOfType.values()) {
            if (handler.support(billType)) {
                return handler;
            }
        }
        return SpringUtil.getBean(DefaultWmsInoutFlowService.class);
    }

}
