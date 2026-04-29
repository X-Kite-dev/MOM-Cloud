package com.kitex.mom.iot.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.entity.IotDeviceEventEntity;
import com.kitex.mom.iot.mapper.IotDeviceEventMapper;
import com.kitex.mom.iot.service.IotDeviceEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * IoT设备事件数据服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceEventServiceImpl extends ServiceImpl<IotDeviceEventMapper, IotDeviceEventEntity> implements IotDeviceEventService {

    @Override
    public IPage<IotDeviceEventEntity> getDeviceEventPage(Page<IotDeviceEventEntity> page, IotDeviceEventEntity event) {
        LambdaQueryWrapper<IotDeviceEventEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 设置查询条件
        if (event != null) {
            queryWrapper.eq(event.getDeviceId() != null, IotDeviceEventEntity::getDeviceId, event.getDeviceId())
                    .eq(StrUtil.isNotBlank(event.getDeviceCode()), IotDeviceEventEntity::getDeviceCode, event.getDeviceCode())
                    .eq(event.getProductId() != null, IotDeviceEventEntity::getProductId, event.getProductId())
                    .eq(StrUtil.isNotBlank(event.getProductCode()), IotDeviceEventEntity::getProductCode, event.getProductCode())
                    .eq(StrUtil.isNotBlank(event.getIdentifier()), IotDeviceEventEntity::getIdentifier, event.getIdentifier())
                    .eq(StrUtil.isNotBlank(event.getName()), IotDeviceEventEntity::getName, event.getName())
                    .eq(StrUtil.isNotBlank(event.getEventType()), IotDeviceEventEntity::getEventType, event.getEventType());

            // 如果有事件时间范围，添加时间范围条件
            if (event.getEventTime() != null) {
                // 假设这里使用事件时间作为开始时间，查询一天内的数据
                LocalDateTime startTime = event.getEventTime();
                LocalDateTime endTime = startTime.plusDays(1);
                queryWrapper.between(IotDeviceEventEntity::getEventTime, startTime, endTime);
            }
        }

        // 按事件时间降序排序
        queryWrapper.orderByDesc(IotDeviceEventEntity::getEventTime);

        return this.page(page, queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeviceEvent(IotDeviceEventEntity event) {
        if (event == null) {
            return false;
        }

        try {
            // 如果没有设置事件时间，使用当前时间
            if (event.getEventTime() == null) {
                event.setEventTime(LocalDateTime.now());
            }

            return this.save(event);
        } catch (Exception e) {
            log.error("保存设备事件数据失败", e);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean batchSaveDeviceEvent(List<IotDeviceEventEntity> events) {
        if (CollUtil.isEmpty(events)) {
            return false;
        }

        try {
            // 设置组织ID和事件时间
            LocalDateTime now = LocalDateTime.now();

            for (IotDeviceEventEntity event : events) {

                if (event.getEventTime() == null) {
                    event.setEventTime(now);
                }
            }

            return this.saveBatch(events);
        } catch (Exception e) {
            log.error("批量保存设备事件数据失败", e);
            return false;
        }
    }

    @Override
    public List<IotDeviceEventEntity> getRecentEventsByDeviceId(Long deviceId, Integer limit) {
        if (deviceId == null) {
            return CollUtil.newArrayList();
        }

        // 默认限制50条
        int queryLimit = limit != null && limit > 0 ? limit : 50;

        LambdaQueryWrapper<IotDeviceEventEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceEventEntity::getDeviceId, deviceId)
                .orderByDesc(IotDeviceEventEntity::getEventTime)
                .last("LIMIT " + queryLimit);

        return this.list(queryWrapper);
    }

    @Override
    public List<IotDeviceEventEntity> getRecentEventsByDeviceCode(String deviceCode, Integer limit) {
        if (StrUtil.isBlank(deviceCode)) {
            return CollUtil.newArrayList();
        }

        // 默认限制50条
        int queryLimit = limit != null && limit > 0 ? limit : 50;

        LambdaQueryWrapper<IotDeviceEventEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceEventEntity::getDeviceCode, deviceCode)
                .orderByDesc(IotDeviceEventEntity::getEventTime)
                .last("LIMIT " + queryLimit);

        return this.list(queryWrapper);
    }

    @Override
    public List<IotDeviceEventEntity> getByDeviceId(Long deviceId) {
        return baseMapper.selectByDeviceId(deviceId);
    }

    @Override
    public List<IotDeviceEventEntity> getByDeviceCode(String deviceCode) {
        return baseMapper.selectByDeviceCode(deviceCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveEvent(Long deviceId, String deviceCode, Long productId, String productCode,
                             String identifier, String name, String eventType, Map<String, Object> eventData, String factoryCode) {
        IotDeviceEventEntity event = new IotDeviceEventEntity();
        event.setDeviceId(deviceId);
        event.setDeviceCode(deviceCode);
        event.setProductId(productId);
        event.setProductCode(productCode);
        event.setIdentifier(identifier);
        event.setName(name);
        event.setEventType(eventType);
        event.setEventData(JSONUtil.toJsonStr(eventData));
        event.setEventTime(LocalDateTime.now());

        return this.save(event);
    }
} 