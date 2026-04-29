package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.entity.IotNetworkComponentEntity;
import com.kitex.mom.iot.mapper.IotNetworkComponentMapper;
import com.kitex.mom.iot.service.IotNetworkComponentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IoT网络组件服务实现类
 *
 * @author system
 * @date 2025-03-12
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IotNetworkComponentServiceImpl extends ServiceImpl<IotNetworkComponentMapper, IotNetworkComponentEntity> implements IotNetworkComponentService {

    @Override
    public IPage<IotNetworkComponentEntity> queryPage(Page<IotNetworkComponentEntity> page, IotNetworkComponentEntity networkComponent) {
        LambdaQueryWrapper<IotNetworkComponentEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 构建查询条件
        queryWrapper.like(StrUtil.isNotBlank(networkComponent.getName()), IotNetworkComponentEntity::getName, networkComponent.getName()).eq(StrUtil.isNotBlank(networkComponent.getType()), IotNetworkComponentEntity::getType, networkComponent.getType()).eq(StrUtil.isNotBlank(networkComponent.getClusterMode()), IotNetworkComponentEntity::getClusterMode, networkComponent.getClusterMode()).eq(networkComponent.getFactoryCode() != null, IotNetworkComponentEntity::getFactoryCode, networkComponent.getFactoryCode()).orderByDesc(IotNetworkComponentEntity::getCreateTime);

        return baseMapper.selectPage(page, queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveNetworkComponent(IotNetworkComponentEntity networkComponent) {
        // 验证参数
//        validateNetworkComponent(networkComponent);

        // 设置创建时间
        networkComponent.setCreateTime(LocalDateTime.now());
        networkComponent.setDelFlag("0");

        return this.save(networkComponent);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateNetworkComponent(IotNetworkComponentEntity networkComponent) {
        // 验证参数
//        validateNetworkComponent(networkComponent);

        // 检查是否存在
        IotNetworkComponentEntity existingComponent = this.getById(networkComponent.getId());
        Assert.notNull(existingComponent, "网络组件不存在");

        // 设置更新时间
        networkComponent.setUpdateTime(LocalDateTime.now());

        return this.updateById(networkComponent);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteNetworkComponent(List<Long> ids) {
        // 检查是否有设备正在使用该网络组件
        checkNetworkComponentUsage(ids);

        return this.removeByIds(ids);
    }

    @Override
    public List<IotNetworkComponentEntity> getNetworkComponentsByType(String type) {
        LambdaQueryWrapper<IotNetworkComponentEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotNetworkComponentEntity::getType, type).eq(IotNetworkComponentEntity::getDelFlag, "0").orderByDesc(IotNetworkComponentEntity::getCreateTime);

        return this.list(queryWrapper);
    }

    @Override
    public IotNetworkComponentEntity getNetworkComponentById(Long id) {
        return this.getById(id);
    }


    /**
     * 检查网络组件是否被使用
     */
    private void checkNetworkComponentUsage(List<Long> ids) {
        // TODO: 实现检查逻辑，确保没有设备正在使用这些网络组件
        // 例如：查询产品接入表，检查是否有产品使用了这些网络组件
    }
} 