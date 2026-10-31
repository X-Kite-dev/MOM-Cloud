package com.kitex.mom.wms.service.outNotice;

import org.springframework.stereotype.Component;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DefaultWmsOutNoticeService extends AbstractWmsOutNoticeHandler {

    /**
     * 服务内部持久化端口，复用原仓库配置、数量、货权及指定库存校验。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean persistAcceptedSourceNotice(WmsOutNoticeEntity notice) {
        return persistNotice(notice);
    }

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
