package com.kitex.mom.wms.service.outNotice;

import org.springframework.stereotype.Component;

@Component
public class DefaultWmsOutNoticeService extends AbstractWmsOutNoticeHandler {

    /**
     * 支持的单据类型
     *
     * @param billType
     */
    @Override
    public Boolean support(String billType) {
        return Boolean.TRUE;
    }

}
