package com.kitex.mom.wms.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.wms.entity.WmsContainerUseHistoryEntity;
import com.kitex.mom.wms.mapper.WmsContainerUseHistoryMapper;
import com.kitex.mom.wms.service.WmsContainerUseHistoryService;
import org.springframework.stereotype.Service;

/**
 * 容器占用历史
 *
 * @author kitex
 * @date 2025-01-02 15:06:10
 */
@Service
public class WmsContainerUseHistoryServiceImpl extends ServiceImpl<WmsContainerUseHistoryMapper, WmsContainerUseHistoryEntity> implements WmsContainerUseHistoryService {

    @Override
    public Page<WmsContainerUseHistoryEntity> pageHistory(Page page, WmsContainerUseHistoryEntity params) {
        return baseMapper.fetchHistories(page, params);
    }
}