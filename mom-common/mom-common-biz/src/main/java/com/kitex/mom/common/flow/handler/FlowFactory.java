package com.kitex.mom.common.flow.handler;

import cn.hutool.extra.spring.SpringUtil;
import com.kitex.mom.common.flow.handler.service.FlowableFlowService;

import java.util.Map;

/**
 * 入库单工厂类
 */
public class FlowFactory {

    /**
     * 获取对应的实现类
     */
    public static FlowHandler getHandler(String billType) {
        Map<String, AbstractFlowHandler> beansOfType = SpringUtil.getBeansOfType(AbstractFlowHandler.class);
        for (FlowHandler handler : beansOfType.values()) {
            if (handler.support(billType)) {
                return handler;
            }
        }
        return SpringUtil.getBean(FlowableFlowService.class);
    }

}
