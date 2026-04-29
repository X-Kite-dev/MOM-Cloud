package com.kitex.mom.wms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.wms.entity.WmsContainerUseHistoryEntity;

public interface WmsContainerUseHistoryService extends IService<WmsContainerUseHistoryEntity> {

    Page<WmsContainerUseHistoryEntity> pageHistory(Page page, WmsContainerUseHistoryEntity params);
}