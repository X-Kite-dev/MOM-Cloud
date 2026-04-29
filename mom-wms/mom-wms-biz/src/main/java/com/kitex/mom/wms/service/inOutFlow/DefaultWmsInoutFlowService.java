package com.kitex.mom.wms.service.inOutFlow;

import org.springframework.stereotype.Component;

@Component
public class DefaultWmsInoutFlowService extends AbstractWmsInoutFlowHandler {

    /**
     * 支持的单据类型
     *
     * @param billType
     */
    @Override
    public Boolean support(String billType) {
        return Boolean.FALSE;
    }
}
