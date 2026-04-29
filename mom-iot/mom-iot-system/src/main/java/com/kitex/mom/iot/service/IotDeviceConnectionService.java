package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.entity.IotDeviceConnectionEntity;

/**
 * IoT设备连接服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotDeviceConnectionService extends IService<IotDeviceConnectionEntity> {

    /**
     * 分页查询设备连接列表
     *
     * @param page       分页参数
     * @param connection 查询条件
     * @return 分页结果
     */
    IPage<IotDeviceConnectionEntity> getDeviceConnectionPage(Page<IotDeviceConnectionEntity> page, IotDeviceConnectionEntity connection);

    /**
     * 根据设备ID获取最新连接信息
     *
     * @param deviceId 设备ID
     * @return 连接信息
     */
    IotDeviceConnectionEntity getLatestConnectionByDeviceId(Long deviceId);

    /**
     * 记录设备连接
     *
     * @param connection 连接信息
     * @return 操作结果
     */
    Boolean recordDeviceConnection(IotDeviceConnectionEntity connection);

    /**
     * 记录设备断开连接
     *
     * @param clientId 客户端ID
     * @return 操作结果
     */
    Boolean recordDeviceDisconnection(String clientId);
} 