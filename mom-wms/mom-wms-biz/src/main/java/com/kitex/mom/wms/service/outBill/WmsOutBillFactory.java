package com.kitex.mom.wms.service.outBill;

import cn.hutool.extra.spring.SpringUtil;

import java.util.Map;

/**
 * 入库单工厂类
 */
public class WmsOutBillFactory {

    /**
     * 获取对应的实现类
     */
    public static WmsOutBillHandler getHandler(String billType) {
        Map<String, AbstractWmsOutBillHandler> beansOfType = SpringUtil.getBeansOfType(AbstractWmsOutBillHandler.class);
        for (WmsOutBillHandler handler : beansOfType.values()) {
            if (handler.support(billType)) {
                return handler;
            }
        }
        return SpringUtil.getBean(DefaultWmsOutBillService.class);
    }

}
