package com.kitex.mom.wms.service.inNotice;

import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import org.springframework.stereotype.Component;

@Component
public class DefaultWmsInNoticeService extends AbstractWmsInNoticeHandler {

    /**
     * 作废后的回调
     *
     * @param noticeEntity
     */
    @Override
    public void voidObjsBack(WmsInNoticeEntity noticeEntity) {

    }

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
