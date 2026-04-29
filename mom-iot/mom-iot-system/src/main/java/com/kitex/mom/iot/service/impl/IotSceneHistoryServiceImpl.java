package com.kitex.mom.iot.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.entity.IotSceneHistoryEntity;
import com.kitex.mom.iot.mapper.IotSceneHistoryMapper;
import com.kitex.mom.iot.service.IotSceneHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场景联动历史记录Service实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotSceneHistoryServiceImpl extends ServiceImpl<IotSceneHistoryMapper, IotSceneHistoryEntity> implements IotSceneHistoryService {

    @Override
    public IPage<IotSceneHistoryEntity> getHistoryPage(Page<IotSceneHistoryEntity> page, IotSceneHistoryEntity query) {
        return baseMapper.selectHistoryPage(page, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addHistory(IotSceneHistoryEntity entity) {
        return this.save(entity);
    }
} 