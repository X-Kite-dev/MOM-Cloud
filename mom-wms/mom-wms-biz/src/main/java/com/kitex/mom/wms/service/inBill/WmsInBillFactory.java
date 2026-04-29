package com.kitex.mom.wms.service.inBill;

import cn.hutool.extra.spring.SpringUtil;

import java.util.Map;

/**
 * 入库单工厂类
 */
public class WmsInBillFactory {

    /**
     * 获取对应的实现类
     */
    public static WmsInBillHandler getHandler(String billType) {
        Map<String, AbstractWmsInBillHandler> beansOfType = SpringUtil.getBeansOfType(AbstractWmsInBillHandler.class);
        for (WmsInBillHandler handler : beansOfType.values()) {
            if (handler.support(billType)) {
                return handler;
            }
        }
        return SpringUtil.getBean(DefaultWmsInBillService.class);
    }

}
