package com.kitex.mom.iot.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.entity.IotDeviceConnectionEntity;
import com.kitex.mom.iot.entity.IotDeviceEntity;
import com.kitex.mom.iot.mapper.IotDeviceConnectionMapper;
import com.kitex.mom.iot.service.IotDeviceConnectionService;
import com.kitex.mom.iot.service.IotDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * IoT设备连接服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceConnectionServiceImpl extends ServiceImpl<IotDeviceConnectionMapper, IotDeviceConnectionEntity> implements IotDeviceConnectionService {

    private final IotDeviceService deviceService;

    @Override
    public IPage<IotDeviceConnectionEntity> getDeviceConnectionPage(Page<IotDeviceConnectionEntity> page, IotDeviceConnectionEntity connection) {
        LambdaQueryWrapper<IotDeviceConnectionEntity> queryWrapper = new LambdaQueryWrapper<>();

        // 设置查询条件
        if (connection != null) {
            queryWrapper.eq(connection.getDeviceId() != null, IotDeviceConnectionEntity::getDeviceId, connection.getDeviceId())
                    .eq(StrUtil.isNotBlank(connection.getDeviceCode()), IotDeviceConnectionEntity::getDeviceCode, connection.getDeviceCode())
                    .eq(connection.getProductId() != null, IotDeviceConnectionEntity::getProductId, connection.getProductId())
                    .eq(StrUtil.isNotBlank(connection.getProductCode()), IotDeviceConnectionEntity::getProductCode, connection.getProductCode())
                    .eq(StrUtil.isNotBlank(connection.getClientId()), IotDeviceConnectionEntity::getClientId, connection.getClientId())
                    .eq(connection.getConnectStatus() != null, IotDeviceConnectionEntity::getConnectStatus, connection.getConnectStatus());
        }

        // 按连接时间降序排序
        queryWrapper.orderByDesc(IotDeviceConnectionEntity::getConnectTime);

        return this.page(page, queryWrapper);
    }

    @Override
    public IotDeviceConnectionEntity getLatestConnectionByDeviceId(Long deviceId) {
        if (deviceId == null) {
            return null;
        }

        LambdaQueryWrapper<IotDeviceConnectionEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotDeviceConnectionEntity::getDeviceId, deviceId)
                .orderByDesc(IotDeviceConnectionEntity::getConnectTime)
                .last("LIMIT 1");

        return this.getOne(queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean recordDeviceConnection(IotDeviceConnectionEntity connection) {
        if (connection == null || connection.getDeviceId() == null || StrUtil.isBlank(connection.getClientId())) {
            log.error("设备连接信息不完整");
            return false;
        }

        try {
            // 设置连接状态和时间
            connection.setConnectStatus(1); // 1-连接中
            connection.setConnectTime(LocalDateTime.now());
            connection.setDisconnectTime(null);

            // 保存连接记录
            boolean saved = this.save(connection);

            if (saved) {
                // 更新设备状态为在线
                IotDeviceEntity device = new IotDeviceEntity();
                device.setId(connection.getDeviceId());
                device.setStatus(2); // 2-在线
                device.setLastOnlineTime(LocalDateTime.now());
                device.setIpAddress(connection.getIpAddress());
                deviceService.updateById(device);
            }

            return saved;
        } catch (Exception e) {
            log.error("记录设备连接失败", e);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean recordDeviceDisconnection(String clientId) {
        if (StrUtil.isBlank(clientId)) {
            log.error("客户端ID为空");
            return false;
        }

        try {
            // 查询最新的连接记录
            LambdaQueryWrapper<IotDeviceConnectionEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(IotDeviceConnectionEntity::getClientId, clientId)
                    .eq(IotDeviceConnectionEntity::getConnectStatus, 1) // 1-连接中
                    .orderByDesc(IotDeviceConnectionEntity::getConnectTime)
                    .last("LIMIT 1");

            IotDeviceConnectionEntity connection = this.getOne(queryWrapper);

            if (connection == null) {
                log.warn("未找到客户端ID为{}的连接记录", clientId);
                return false;
            }

            // 更新连接状态和断开时间
            connection.setConnectStatus(2); // 2-已断开
            connection.setDisconnectTime(LocalDateTime.now());
            boolean updated = this.updateById(connection);

            if (updated) {
                // 更新设备状态为离线
                IotDeviceEntity device = new IotDeviceEntity();
                device.setId(connection.getDeviceId());
                device.setStatus(3); // 3-离线
                deviceService.updateById(device);
            }

            return updated;
        } catch (Exception e) {
            log.error("记录设备断开连接失败", e);
            return false;
        }
    }
} 