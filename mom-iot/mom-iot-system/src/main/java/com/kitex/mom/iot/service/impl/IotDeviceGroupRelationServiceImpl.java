package com.kitex.mom.iot.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.dto.IotDeviceGroupRelationDTO;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.entity.IotDeviceGroupRelationEntity;
import com.kitex.mom.iot.mapper.IotDeviceGroupRelationMapper;
import com.kitex.mom.iot.service.IotDeviceGroupRelationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * IoT设备分组关联服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceGroupRelationServiceImpl extends ServiceImpl<IotDeviceGroupRelationMapper, IotDeviceGroupRelationEntity> implements IotDeviceGroupRelationService {

    @Override
    public IPage<IotDeviceEntity> getDevicesByGroupId(Page<IotDeviceEntity> page, Long groupId, IotDeviceEntity device) {
        return baseMapper.selectDevicesByGroupId(page, groupId, device);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean addDevicesToGroup(IotDeviceGroupRelationDTO relationDTO) {
        if (relationDTO.getGroupId() == null || CollUtil.isEmpty(relationDTO.getDeviceIds())) {
            return false;
        }

        List<IotDeviceGroupRelationEntity> relations = new ArrayList<>();
        for (Long deviceId : relationDTO.getDeviceIds()) {
            // 检查关联是否已存在
            LambdaQueryWrapper<IotDeviceGroupRelationEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(IotDeviceGroupRelationEntity::getGroupId, relationDTO.getGroupId())
                    .eq(IotDeviceGroupRelationEntity::getDeviceId, deviceId);
            if (this.count(queryWrapper) > 0) {
                continue;
            }

            // 创建关联
            IotDeviceGroupRelationEntity relation = new IotDeviceGroupRelationEntity();
            relation.setGroupId(relationDTO.getGroupId());
            relation.setDeviceId(deviceId);
            relations.add(relation);
        }

        if (CollUtil.isEmpty(relations)) {
            return true;
        }

        return this.saveBatch(relations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDevicesFromGroup(IotDeviceGroupRelationDTO relationDTO) {
        if (relationDTO.getGroupId() == null || CollUtil.isEmpty(relationDTO.getDeviceIds())) {
            return false;
        }

        // 实现从分组中移除设备的逻辑
        return false; // 临时返回，需要根据实际需求实现
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeAllGroupsByDeviceId(Long deviceId) {
        // 实现移除设备的所有分组关联的逻辑
        return false; // 临时返回，需要根据实际需求实现
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeAllDevicesByGroupId(Long groupId) {
        // 实现移除分组的所有设备关联的逻辑
        return false; // 临时返回，需要根据实际需求实现
    }
} 